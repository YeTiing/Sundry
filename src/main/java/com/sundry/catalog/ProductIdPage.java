package com.sundry.catalog;

import java.util.List;

/**
 * 商品列表缓存只存 ID 和总数，卡片按商品单独缓存。
 */
public record ProductIdPage(List<Long> ids, long total, int page, int size) {
}
