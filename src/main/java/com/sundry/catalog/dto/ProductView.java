package com.sundry.catalog.dto;

import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品展示项。coverUrl 由后端按存储配置拼出，前端直接用；
 * 数字文件只暴露 id，不暴露对象 key 或下载地址。
 */
public record ProductView(Long id, String name, String description, ProductType type,
                          BigDecimal price, Long categoryId, String categoryName,
                          Long coverFileId, String coverUrl, Long digitalFileId,
                          ProductStatus status, LocalDateTime createdAt) {
}
