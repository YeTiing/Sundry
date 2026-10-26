package com.sundry.assistant;

import com.sundry.common.api.PageResponse;
import com.sundry.order.OrderService;
import com.sundry.order.OrderStatus;
import com.sundry.order.dto.OrderItemView;
import com.sundry.order.dto.OrderView;
import com.sundry.user.Role;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只查当前登录用户的订单；userId 来自 JWT，模型无法指定别人。
 */
@Component
public class ListMyOrdersTool implements AssistantTool {

    private static final int SCAN_SIZE = 20;
    private static final int SHOW_SIZE = 5;
    private static final int MAX_ITEM_NAMES = 3;
    private static final int MAX_ITEM_NAME_LENGTH = 30;

    private static final Map<OrderStatus, String> LABELS = Map.ofEntries(
            Map.entry(OrderStatus.PENDING_PAYMENT, "待支付"),
            Map.entry(OrderStatus.PAID, "待发货"),
            Map.entry(OrderStatus.PACKING, "打包中"),
            Map.entry(OrderStatus.SHIPPED, "已发货"),
            Map.entry(OrderStatus.COMPLETED, "已完成"),
            Map.entry(OrderStatus.CANCELLED, "已取消"),
            Map.entry(OrderStatus.REFUND_REQUESTED, "退款审核中"),
            Map.entry(OrderStatus.RETURN_REQUESTED, "退货审核中"),
            Map.entry(OrderStatus.RETURNING, "待寄回"),
            Map.entry(OrderStatus.RETURN_SHIPPED, "退货已寄回"),
            Map.entry(OrderStatus.REFUNDED, "已退款"));

    private final OrderService orderService;

    public ListMyOrdersTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String name() {
        return "list_my_orders";
    }

    @Override
    public String description() {
        return "查询当前登录用户自己的订单。用户问『我买了什么』『我的订单』『物流』时使用。";
    }

    @Override
    public Set<Role> roles() {
        return Set.of(Role.USER, Role.ADMIN, Role.SUPER_ADMIN);
    }

    @Override
    public Map<String, Object> parameters() {
        List<String> statuses = Arrays.stream(OrderStatus.values()).map(Enum::name).toList();
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of("type", "string", "enum", statuses)),
                "additionalProperties", false);
    }

    @Override
    public AssistantToolResult execute(AssistantToolContext context, JsonNode args) {
        OrderStatus status = parseStatus(args.get("status"));
        PageResponse<OrderView> page = orderService.userPage(context.userId(), 1, SCAN_SIZE);
        List<OrderView> orders = page.items().stream()
                .filter(order -> status == null || order.status() == status)
                .limit(SHOW_SIZE)
                .toList();

        if (orders.isEmpty()) {
            return new AssistantToolResult(status == null
                    ? "你还没有订单。"
                    : "最近 " + SCAN_SIZE + " 个订单里没有「" + label(status) + "」的订单。");
        }

        StringBuilder reply = new StringBuilder(status == null
                ? "你最近买过：\n"
                : "你最近买过（" + label(status) + "）：\n");
        for (OrderView order : orders) {
            reply.append("- ").append(summary(order))
                    .append("｜").append(label(order.status()))
                    .append("｜¥").append(plain(order.totalAmount()));
            if (order.createdAt() != null) {
                reply.append("｜").append(order.createdAt().toLocalDate());
            }
            reply.append('\n');
        }
        return new AssistantToolResult(reply.toString().trim());
    }

    /**
     * 用户问“买了什么”时优先给商品名，订单号留在订单页看。
     */
    private String summary(OrderView order) {
        List<OrderItemView> items = order.items();
        if (items == null || items.isEmpty()) {
            return "商品明细缺失";
        }
        int shown = Math.min(MAX_ITEM_NAMES, items.size());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append('、');
            }
            text.append(itemLabel(items.get(i)));
        }
        if (items.size() > shown) {
            text.append(" 等 ").append(items.size()).append(" 种商品");
        }
        return text.toString();
    }

    private String itemLabel(OrderItemView item) {
        String name = item.productName() == null || item.productName().isBlank()
                ? "商品" : item.productName().trim();
        if (name.length() > MAX_ITEM_NAME_LENGTH) {
            name = name.substring(0, MAX_ITEM_NAME_LENGTH) + "…";
        }
        return item.quantity() > 1 ? name + " ×" + item.quantity() : name;
    }

    private OrderStatus parseStatus(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AssistantToolException("INVALID_ARGS", "订单状态格式不对。");
        }
        try {
            return OrderStatus.valueOf(node.asText());
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个订单状态。");
        }
    }

    private String label(OrderStatus status) {
        return LABELS.getOrDefault(status, status.name());
    }

    private String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
