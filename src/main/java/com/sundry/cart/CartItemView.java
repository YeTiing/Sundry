package com.sundry.cart;

import com.sundry.catalog.ProductType;

import java.math.BigDecimal;

/**
 * 购物车展示项。available 表示商品当前是否还能购买（已上架且未删除）。
 */
public record CartItemView(Long id, Long productId, String productName, ProductType productType,
                           BigDecimal price, int quantity, int stock, String coverUrl,
                           boolean available) {
}
