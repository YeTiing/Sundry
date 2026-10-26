package com.sundry.assistant;

import com.sundry.order.OrderSort;
import com.sundry.order.OrderStatus;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订单类助手工具共用的参数解析与展示文案。模型给的值一律在这里校验。
 */
final class AssistantOrderArgs {

    static final int MAX_KEYWORD_LENGTH = 20;

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

    private AssistantOrderArgs() {
    }

    static OrderStatus status(JsonNode node) {
        String text = enumText(node, "订单状态");
        if (text == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(text);
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个订单状态。");
        }
    }

    static OrderTimeRange timeRange(JsonNode node) {
        String text = enumText(node, "时间范围");
        if (text == null) {
            return null;
        }
        try {
            return OrderTimeRange.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个时间范围。");
        }
    }

    static String keyword(JsonNode node) {
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

    static OrderSort sort(JsonNode node) {
        String text = enumText(node, "排序方式");
        if (text == null) {
            return OrderSort.NEWEST;
        }
        try {
            return OrderSort.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new AssistantToolException("INVALID_ARGS", "不支持这个排序方式。");
        }
    }

    static int limit(JsonNode node, int defaultValue, int max) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new AssistantToolException("INVALID_ARGS", "展示条数必须是整数。");
        }
        int limit = node.asInt();
        if (limit < 1 || limit > max) {
            throw new AssistantToolException("INVALID_ARGS",
                    "展示条数需要在 1~" + max + " 之间。");
        }
        return limit;
    }

    static String statusLabel(OrderStatus status) {
        return LABELS.getOrDefault(status, status.name());
    }

    static String describe(OrderStatus status, OrderTimeRange preset, String keyword) {
        List<String> parts = new ArrayList<>();
        if (preset != null) {
            parts.add(preset.label());
        }
        if (status != null) {
            parts.add(statusLabel(status));
        }
        if (keyword != null) {
            parts.add("含「" + keyword + "」");
        }
        return String.join("，", parts);
    }

    private static String enumText(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AssistantToolException("INVALID_ARGS", name + "格式不对。");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
