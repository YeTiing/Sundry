package com.sundry.assistant;

import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductType;
import com.sundry.catalog.dto.ProductView;
import com.sundry.feed.FeedService;
import com.sundry.user.Role;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品推荐/筛选：有筛选条件走商品表搜索，无条件下用首页推荐排序。
 * 只返回在售商品，价格与文本参数全部由 Java 校验。
 */
@Component
public class RecommendProductsTool implements AssistantTool {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    private static final int MAX_TEXT = 20;
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");

    private final ProductService productService;
    private final FeedService feedService;

    public RecommendProductsTool(ProductService productService, FeedService feedService) {
        this.productService = productService;
        this.feedService = feedService;
    }

    @Override
    public String name() {
        return "recommend_products";
    }

    @Override
    public String description() {
        return "搜索并推荐在售商品。用户问『有没有 XX 推荐』『想买 XX』『推荐几个 XX』时使用。"
                + "可按商品名称/描述/标签关键词、分类名称、价格区间筛选。";
    }

    @Override
    public Set<Role> roles() {
        return Set.of(Role.USER, Role.ADMIN, Role.SUPER_ADMIN);
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of(
                "type", "string", "minLength", 1, "maxLength", MAX_TEXT,
                "description", "商品名称/描述/标签关键词，例如鼠标、耳机"));
        properties.put("category", Map.of(
                "type", "string", "minLength", 1, "maxLength", MAX_TEXT,
                "description", "分类名称，例如数码配件、实物"));
        properties.put("minPrice", Map.of(
                "type", "number", "minimum", 0, "maximum", MAX_PRICE));
        properties.put("maxPrice", Map.of(
                "type", "number", "minimum", 0, "maximum", MAX_PRICE));
        properties.put("limit", Map.of(
                "type", "integer", "minimum", 1, "maximum", MAX_LIMIT));
        return Map.of(
                "type", "object",
                "properties", properties,
                "additionalProperties", false);
    }

    @Override
    public AssistantToolResult execute(AssistantToolContext context, JsonNode args) {
        String keyword = text(args.get("keyword"), "商品关键词");
        String category = text(args.get("category"), "分类名称");
        BigDecimal minPrice = price(args.get("minPrice"), "最低价");
        BigDecimal maxPrice = price(args.get("maxPrice"), "最高价");
        int limit = AssistantOrderArgs.limit(args.get("limit"), DEFAULT_LIMIT, MAX_LIMIT);

        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new AssistantToolException("INVALID_ARGS", "最低价不能高于最高价。");
        }

        List<ProductView> products;
        if (keyword == null && category == null && minPrice == null && maxPrice == null) {
            products = personalized(context.userId(), limit);
            if (products.isEmpty()) {
                products = productService.recommend(null, null, null, null, limit);
            }
        } else {
            products = productService.recommend(keyword, category, minPrice, maxPrice, limit);
        }

        String criteria = describe(keyword, category, minPrice, maxPrice);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "products");
        data.put("criteria", criteria.isEmpty() ? "为你推荐" : criteria);
        data.put("products", products.stream().map(this::card).toList());

        if (products.isEmpty()) {
            return new AssistantToolResult(
                    "没有找到符合条件的在售商品。可以换个关键词或放宽价格。", data);
        }

        StringBuilder reply = new StringBuilder(criteria.isEmpty()
                ? "给你推荐这些商品：\n"
                : "找到这些商品（" + criteria + "）：\n");
        for (ProductView product : products) {
            reply.append("- ").append(product.name())
                    .append("｜¥").append(plain(product.price()))
                    .append("｜").append(product.categoryName())
                    .append('\n');
        }
        return new AssistantToolResult(reply.toString().trim(), data);
    }

    private List<ProductView> personalized(Long userId, int limit) {
        List<Long> ids = feedService.rankIds(userId).stream()
                .distinct().limit(limit).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductView> views = productService.findPublicBatch(ids);
        List<ProductView> products = new ArrayList<>();
        for (Long id : ids) {
            ProductView view = views.get(id);
            if (view != null && (view.type() == ProductType.DIGITAL
                    || (view.stock() != null && view.stock() > 0))) {
                products.add(view);
            }
        }
        return products;
    }

    private Map<String, Object> card(ProductView product) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", product.id());
        card.put("name", product.name());
        card.put("price", plain(product.price()));
        card.put("category", product.categoryName());
        card.put("coverUrl", product.coverUrl());
        card.put("type", product.type().name());
        return card;
    }

    private String describe(String keyword, String category,
                            BigDecimal minPrice, BigDecimal maxPrice) {
        List<String> parts = new ArrayList<>();
        if (keyword != null) {
            parts.add("关键词「" + keyword + "」");
        }
        if (category != null) {
            parts.add("分类「" + category + "」");
        }
        if (minPrice != null) {
            parts.add("≥¥" + plain(minPrice));
        }
        if (maxPrice != null) {
            parts.add("≤¥" + plain(maxPrice));
        }
        return String.join("，", parts);
    }

    private String text(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AssistantToolException("INVALID_ARGS", name + "格式不对。");
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_TEXT) {
            throw new AssistantToolException("INVALID_ARGS",
                    name + "不能超过 " + MAX_TEXT + " 个字。");
        }
        return value;
    }

    private BigDecimal price(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new AssistantToolException("INVALID_ARGS", name + "必须是数字。");
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.compareTo(MAX_PRICE) > 0) {
            throw new AssistantToolException("INVALID_ARGS", name + "超出范围。");
        }
        return value;
    }

    private String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
