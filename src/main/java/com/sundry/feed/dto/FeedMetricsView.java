package com.sundry.feed.dto;

import java.util.List;

/**
 * 离线评估结果。CTR 的分母永远是曝光；NDCG 只统计有点击的 trace。
 */
public record FeedMetricsView(int windowDays, long exposures, long clicks, double ctr,
                              List<AlgorithmMetric> byAlgorithm,
                              List<PositionMetric> byPosition,
                              NdcgMetric ndcg,
                              CoverageMetric coverage,
                              DiversityMetric diversity) {

    public record AlgorithmMetric(String algorithm, long exposures, long clicks, double ctr) {
    }

    public record PositionMetric(int position, long exposures, long clicks, double ctr) {
    }

    public record NdcgMetric(double overall, double ai, double rule,
                             int tracesWithClicks, int k) {
    }

    public record CoverageMetric(long exposedProducts, long onSaleProducts, double rate) {
    }

    public record DiversityMetric(double avgCategoriesPerTrace, int traces) {
    }
}
