package com.sundry.assistant;

import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.dto.ProductView;
import com.sundry.common.exception.BusinessException;
import com.sundry.user.Role;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理员查商品状态；普通用户连工具定义都看不到。
 */
@Component
public class GetProductStatusTool implements AssistantTool {

    private final ProductService productService;

    public GetProductStatusTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public String name() {
        return "get_product_status";
    }

    @Override
    public String description() {
        return "查询指定商品的上下架状态、库存和价格。用户给出商品 id 时使用。";
    }

    @Override
    public Set<Role> roles() {
        return Set.of(Role.ADMIN, Role.SUPER_ADMIN);
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "productId", Map.of("type", "integer", "minimum", 1)),
                "required", List.of("productId"),
                "additionalProperties", false);
    }

    @Override
    public AssistantToolResult execute(AssistantToolContext context, JsonNode args) {
        JsonNode node = args.get("productId");
        if (node == null || !node.canConvertToLong() || node.asLong() <= 0) {
            throw new AssistantToolException("INVALID_ARGS", "请提供有效的商品 id。");
        }
        try {
            ProductView product = productService.findAdmin(node.asLong());
            return new AssistantToolResult("商品「" + product.name() + "」当前"
                    + label(product.status())
                    + "，库存 " + (product.stock() == null ? 0 : product.stock())
                    + "，价格 ¥" + plain(product.price()) + "。");
        } catch (BusinessException ex) {
            throw new AssistantToolException("TOOL_ERROR", "没有找到这个商品。");
        }
    }

    private String label(ProductStatus status) {
        return switch (status) {
            case ON_SALE -> "在售";
            case OFF_SHELF -> "已下架";
            case DRAFT -> "草稿";
        };
    }

    private String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
