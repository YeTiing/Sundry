package com.sundry.feed.dto;

/**
 * 轻量状态：只返回 AI 状态和当前变体 ID，不带商品，供前端轮询。
 */
public record FeedStatusView(String aiStatus, String aiBuildId, String feedBuildId) {
}
