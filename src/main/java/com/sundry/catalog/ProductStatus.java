package com.sundry.catalog;

/**
 * 商品状态。草稿和已下架都不对普通用户展示。
 */
public enum ProductStatus {
    DRAFT,
    ON_SALE,
    OFF_SHELF
}
