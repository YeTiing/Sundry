package com.sundry.catalog.dto;

import com.sundry.catalog.ProductStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品展示项。coverUrl 由后端按存储配置拼出，前端直接用。
 */
public record ProductView(Long id, String name, String description, BigDecimal price,
                          Long categoryId, String categoryName, Long coverFileId, String coverUrl,
                          ProductStatus status, LocalDateTime createdAt) {
}
