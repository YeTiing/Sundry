package com.sundry.feed.dto;

import com.sundry.catalog.dto.ProductView;

import java.util.List;

/**
 * Feed 响应。aiStatus: DISABLED / PENDING / READY / FALLBACK。
 * aiBuildId 供前端判断轮询拿到的结果是不是自己触发的那一次。
 */
public record FeedPage(List<ProductView> items, Long nextCursor, boolean hasMore,
                       String aiStatus, String aiBuildId, String feedBuildId) {
}
