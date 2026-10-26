package com.sundry.assistant;

import com.sundry.common.api.PageResponse;
import com.sundry.order.OrderQueryFilters;
import com.sundry.order.OrderService;
import com.sundry.order.OrderSort;
import com.sundry.order.OrderStatus;
import com.sundry.order.dto.OrderItemView;
import com.sundry.order.dto.OrderView;
import com.sundry.user.Role;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只查当前登录用户的订单；userId 来自 JWT，模型无法指定别人。
 * 时间、排序、条数都在 Java 校验和换算，SQL 只做参数绑定。
 */
@Component
public class ListMyOrdersTool implements AssistantTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int MAX_KEYWORD_LENGTH = 20;
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
        return "查询当前登录用户自己的订单和商品明细。可以按状态、时间范围、商品关键词筛选，"
                + "按时间或金额排序。用户问『我买过什么』『我的订单』『物流』时使用。";
    }

    @Override
    public Set<Role> roles() {
        return Set.of(Role.USER, Role.ADMIN, Role.SUPER_ADMIN);
    }

    @Override
    public Map<String, Object> parameters() {
        List<String> statuses = Arrays.stream(OrderStatus.values()).map(Enum::name).toList();
        List<String> presets = Arrays.stream(TimePreset.values()).map(Enum::name).toList();
        List<String> sorts = Arrays.stream(OrderSort.values()).map(Enum::name).toList();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of("type", "string", "enum", statuses));
        properties.put("timeRange", Map.of(
                "type", "string", "enum", presets,
                "description", "按订单创建时间筛选；无法确定时不要传"));
        properties.put("keyword", Map.of(
                "type", "string", "minLength", 1, "maxLength", MAX_KEYWORD_LENGTH,
                "description", "商品名称关键词，例如耳机、鼠标"));
        properties.put("sort", Map.of("type", "string", "enum", sorts));
        properties.put("limit", Map.of(
                "type", "integer", "minimum", 1, "maximum", MAX_LIMIT));
        return Map.of(
                "type", "object",
                "properties", properties,
                "additionalProperties", false);
    }

    @Override
    public AssistantToolResult execute(AssistantToolContext context, JsonNode args) {
        OrderStatus status = parseStatus(args.get("status"));
        TimePreset preset = parseTimePreset(args.get("timeRange"));
        String keyword = parseKeyword(args.get("keyword"));
        OrderSort sort = parseSort(args.get("sort"));
        int limit = parseLimit(args.get("limit"));

        TimeRange range = preset == null ? null : preset.resolve(LocalDate.now());
        PageResponse<OrderView> page = orderService.userPage(context.userId(),
                new OrderQueryFilters(status,
                        range == null ? null : range.start(),
                        range == null ? null : range.end(),
                        keyword, sort),
                1, limit);
        List<OrderView> orders = page.items();
        String criteria = describe(status, preset, keyword);

        if (orders.isEmpty()) {
            return new AssistantToolResult(criteria.isEmpty()
                    ? "你还没有订单。"
                    : "没有找到符合条件的订单（" + criteria + "）。");
        }

        StringBuilder reply = new StringBuilder("你最近买过");
        if (!criteria.isEmpty()) {
            reply.append("（").append(criteria).append("）");
        }
        reply.append("：\n");
        for (OrderView order : orders) {
            reply.append("- ").append(summary(order))
                    .append("｜").append(label(order.status()))
                    .append("｜¥").append(plain(order.totalAmount()));
            if (order.createdAt() != null) {
                reply.append("｜").append(order.createdAt().toLocalDate());
            }
            reply.append('\n');
        }
        if (page.total() > orders.size()) {
            reply.append("……共 ").append(page.total()).append(" 单");
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

    private String describe(OrderStatus status, TimePreset preset, String keyword) {
        List<String> parts = new ArrayList<>();
        if (preset != null) {
            parts.add(preset.label());
        }
        if (status != null) {
            parts.add(label(status));
        }
        if (keyword != null) {
            parts.add("含「" + keyword + "」");
        }
        return String.join("，", parts);
    }

    private OrderStatus parseStatus(JsonNode node) {
        String text = parseEnumText(node, "订单状态");
        if (text == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(text);
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个订单状态。");
        }
    }

    private TimePreset parseTimePreset(JsonNode node) {
        String text = parseEnumText(node, "时间范围");
        if (text == null) {
            return null;
        }
        try {
            return TimePreset.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个时间范围。");
        }
    }

    private OrderSort parseSort(JsonNode node) {
        String text = parseEnumText(node, "排序方式");
        if (text == null) {
            return OrderSort.NEWEST;
        }
        try {
            return OrderSort.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个排序方式。");
        }
    }

    private String parseKeyword(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AssistantToolException("INVALID_ARGS", "商品关键词格式不对。");
        }
        String keyword = node.asText().trim();
        if (keyword.isEmpty()) {
            return null;
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new AssistantToolException("INVALID_ARGS",
                    "商品关键词不能超过 " + MAX_KEYWORD_LENGTH + " 个字。");
        }
        return keyword;
    }

    private int parseLimit(JsonNode node) {
        if (node == null || node.isNull()) {
            return DEFAULT_LIMIT;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new AssistantToolException("INVALID_ARGS", "展示条数必须是整数。");
        }
        int limit = node.asInt();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AssistantToolException("INVALID_ARGS",
                    "展示条数需要在 1~" + MAX_LIMIT + " 之间。");
        }
        return limit;
    }

    private String parseEnumText(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AssistantToolException("INVALID_ARGS", name + "格式不对。");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private String label(OrderStatus status) {
        return LABELS.getOrDefault(status, status.name());
    }

    private String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {
    }

    private enum TimePreset {
        LAST_7D("近 7 天"),
        LAST_30D("近 30 天"),
        LAST_90D("近 90 天"),
        THIS_MONTH("本月"),
        LAST_MONTH("上月"),
        LAST_YEAR("去年");

        private final String label;

        TimePreset(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        TimeRange resolve(LocalDate today) {
            LocalDate firstOfMonth = today.withDayOfMonth(1);
            return switch (this) {
                case LAST_7D -> new TimeRange(today.minusDays(6).atStartOfDay(),
                        today.plusDays(1).atStartOfDay());
                case LAST_30D -> new TimeRange(today.minusDays(29).atStartOfDay(),
                        today.plusDays(1).atStartOfDay());
                case LAST_90D -> new TimeRange(today.minusDays(89).atStartOfDay(),
                        today.plusDays(1).atStartOfDay());
                case THIS_MONTH -> new TimeRange(firstOfMonth.atStartOfDay(),
                        today.plusDays(1).atStartOfDay());
                case LAST_MONTH -> new TimeRange(firstOfMonth.minusMonths(1).atStartOfDay(),
                        firstOfMonth.atStartOfDay());
                case LAST_YEAR -> new TimeRange(
                        LocalDate.of(today.getYear() - 1, 1, 1).atStartOfDay(),
                        LocalDate.of(today.getYear(), 1, 1).atStartOfDay());
            };
        }
    }
}
