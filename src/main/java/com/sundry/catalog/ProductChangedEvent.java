package com.sundry.catalog;

/**
 * 商品新增/编辑/上下架/删除后触发向量增量更新。
 */
public record ProductChangedEvent(Long productId) {
}
