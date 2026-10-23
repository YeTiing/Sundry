package com.sundry.order.dto;

import com.sundry.catalog.ProductType;

import java.math.BigDecimal;

public record OrderItemView(Long id, Long productId, String productName, ProductType productType,
                            BigDecimal price, int quantity, String coverUrl, Long digitalFileId) {
}
