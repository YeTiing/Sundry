package com.sundry.feed.dto;

import java.util.List;

/**
 * 离线回放：同一批候选上，规则顺序与 AI 顺序的对比。
 */
public record FeedReplayView(int days, int k, int snapshots, int samples,
                             double candidateCoverage,
                             List<StrategyReplay> byStrategy) {

    public record StrategyReplay(String strategy, int samples,
                                 Side rule, Side ai) {
    }

    public record Side(double recallAtK, double ndcgAtK, double lastClickHitRate) {
    }
}
