package com.sundry.assistant;

import com.sundry.order.OrderQueryFilters;
import com.sundry.order.OrderService;
import com.sundry.order.OrderSort;
import com.sundry.order.OrderStatus;
import com.sundry.order.PurchaseSummary;
import com.sundry.user.Role;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个人购买汇总：只统计当前用户的已支付订单，不返回明细。
 */
@Component
public class SummarizeMyPurchasesTool implements AssistantTool {

    private final OrderService orderService;

    public SummarizeMyPurchasesTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String name() {
        return "summarize_my_purchases";
    }

    @Override
    public String description() {
        return "统计当前登录用户买过几次、共几件、累计支付多少钱。只统计已支付订单。"
                + "用户问『买过几次』『一共花了多少』『总共买了多少件』时使用。";
    }

    @Override
    public Set<Role> roles() {
        return Set.of(Role.USER, Role.ADMIN, Role.SUPER_ADMIN);
    }

    @Override
    public Map<String, Object> parameters() {
        List<String> statuses = Arrays.stream(OrderStatus.values()).map(Enum::name).toList();
        List<String> presets = Arrays.stream(OrderTimeRange.values()).map(Enum::name).toList();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of("type", "string", "enum", statuses));
        properties.put("timeRange", Map.of(
                "type", "string", "enum", presets,
                "description", "按支付时间筛选；无法确定时不要传"));
        properties.put("keyword", Map.of(
                "type", "string", "minLength", 1, "maxLength", AssistantOrderArgs.MAX_KEYWORD_LENGTH,
                "description", "商品名称关键词，例如耳机、鼠标"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "additionalProperties", false);
    }

    @Override
    public AssistantToolResult execute(AssistantToolContext context, JsonNode args) {
        OrderStatus status = AssistantOrderArgs.status(args.get("status"));
        OrderTimeRange preset = AssistantOrderArgs.timeRange(args.get("timeRange"));
        String keyword = AssistantOrderArgs.keyword(args.get("keyword"));

        OrderTimeRange.TimeRange range = preset == null ? null : preset.resolve(LocalDate.now());
        PurchaseSummary summary = orderService.summarizePurchases(context.userId(),
                new OrderQueryFilters(status,
                        range == null ? null : range.start(),
                        range == null ? null : range.end(),
                        keyword, OrderSort.NEWEST));
        String criteria = AssistantOrderArgs.describe(status, preset, keyword);

        if (summary.orderCount() == 0) {
            return new AssistantToolResult(criteria.isEmpty()
                    ? "你还没有已支付的订单。"
                    : "没有找到符合条件的已支付订单（" + criteria + "）。");
        }

        StringBuilder reply = new StringBuilder(criteria.isEmpty()
                ? "你买过的已支付订单："
                : criteria + "的已支付订单：");
        reply.append("\n- 买过 ").append(summary.orderCount()).append(" 单")
                .append("\n- 共 ").append(summary.itemCount()).append(" 件商品")
                .append("\n- 累计支付 ¥").append(plain(summary.totalAmount()))
                .append("（未扣退款）");
        return new AssistantToolResult(reply.toString());
    }

    private String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
