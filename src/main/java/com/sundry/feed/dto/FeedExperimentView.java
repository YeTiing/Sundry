package com.sundry.feed.dto;

/**
 * 规则对照组 vs AI 组：CTR 和 95% Wilson 区间。
 */
public record FeedExperimentView(int days, int controlPercent,
                                 Side control, Side ai, Double lift) {

    public record Side(long exposures, long clicks, double ctr,
                       double lower, double upper) {
    }
}
