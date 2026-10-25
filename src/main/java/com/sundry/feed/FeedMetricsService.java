package com.sundry.feed;

import com.sundry.feed.dto.FeedMetricsView;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线评估：CTR、位置 CTR、NDCG、覆盖率、多样性。
 * CTR 分母永远是曝光；NDCG 只统计有点击的 trace。
 */
@Service
public class FeedMetricsService {

    private static final int NDCG_K = 12;
    private static final int MAX_WINDOW_DAYS = 90;
    private static final double LN2 = Math.log(2);

    private final FeedMetricsMapper feedMetricsMapper;

    public FeedMetricsService(FeedMetricsMapper feedMetricsMapper) {
        this.feedMetricsMapper = feedMetricsMapper;
    }

    public FeedMetricsView metrics(int days) {
        int windowDays = Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);

        FeedMetricsMapper.Totals totals = feedMetricsMapper.totals(since);
        long exposures = totals == null ? 0 : nz(totals.getExposures());
        long clicks = totals == null ? 0 : nz(totals.getClicks());

        List<FeedMetricsView.AlgorithmMetric> byAlgorithm = feedMetricsMapper
                .countByAlgorithm(since).stream()
                .map(row -> new FeedMetricsView.AlgorithmMetric(
                        row.getAlgorithm(),
                        nz(row.getExposures()),
                        nz(row.getClicks()),
                        ratio(nz(row.getClicks()), nz(row.getExposures()))))
                .toList();

        List<FeedMetricsView.StrategyMetric> byStrategy = feedMetricsMapper
                .countByStrategy(since).stream()
                .map(row -> new FeedMetricsView.StrategyMetric(
                        row.getStrategy(),
                        nz(row.getExposures()),
                        nz(row.getClicks()),
                        ratio(nz(row.getClicks()), nz(row.getExposures()))))
                .toList();

        List<FeedMetricsView.PositionMetric> byPosition = feedMetricsMapper
                .countByPosition(since).stream()
                .map(row -> new FeedMetricsView.PositionMetric(
                        row.getPosition() == null ? 0 : row.getPosition(),
                        nz(row.getExposures()),
                        nz(row.getClicks()),
                        ratio(nz(row.getClicks()), nz(row.getExposures()))))
                .toList();

        long exposedProducts = feedMetricsMapper.countDistinctExposedProducts(since);
        long onSaleProducts = feedMetricsMapper.countOnSaleProducts();
        FeedMetricsView.CoverageMetric coverage = new FeedMetricsView.CoverageMetric(
                exposedProducts, onSaleProducts,
                ratio(exposedProducts, onSaleProducts));

        Double avgCategories = feedMetricsMapper.avgCategoriesPerTrace(since);
        FeedMetricsView.DiversityMetric diversity = new FeedMetricsView.DiversityMetric(
                avgCategories == null ? 0 : round4(avgCategories),
                (int) feedMetricsMapper.countExposureTraces(since));

        return new FeedMetricsView(
                windowDays,
                exposures,
                clicks,
                ratio(clicks, exposures),
                byAlgorithm,
                byStrategy,
                byPosition,
                ndcg(since),
                coverage,
                diversity);
    }

    private FeedMetricsView.NdcgMetric ndcg(LocalDateTime since) {
        List<FeedMetricEvent> events = feedMetricsMapper.findEventsForNdcg(since, NDCG_K);

        Map<TraceKey, Set<Integer>> clickedPositions = new HashMap<>();
        for (FeedMetricEvent event : events) {
            if (!"CLICK".equals(event.getEventType()) || event.getItemPosition() == null) {
                continue;
            }
            TraceKey key = new TraceKey(event.getTraceId(), event.getUserId(), event.getAlgorithm());
            clickedPositions.computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(event.getItemPosition());
        }

        double overallSum = 0;
        double aiSum = 0;
        double ruleSum = 0;
        int overallCount = 0;
        int aiCount = 0;
        int ruleCount = 0;

        for (Map.Entry<TraceKey, Set<Integer>> entry : clickedPositions.entrySet()) {
            double value = ndcgAtK(entry.getValue(), NDCG_K);
            overallSum += value;
            overallCount++;
            String algorithm = entry.getKey().algorithm();
            if ("AI".equals(algorithm)) {
                aiSum += value;
                aiCount++;
            } else if ("RULE".equals(algorithm)) {
                ruleSum += value;
                ruleCount++;
            }
        }

        return new FeedMetricsView.NdcgMetric(
                average(overallSum, overallCount),
                average(aiSum, aiCount),
                average(ruleSum, ruleCount),
                overallCount,
                NDCG_K);
    }

    private double ndcgAtK(Set<Integer> clickedPositions, int k) {
        int hits = (int) clickedPositions.stream()
                .filter(position -> position >= 1 && position <= k)
                .count();
        if (hits == 0) {
            return 0;
        }
        double dcg = 0;
        for (int position = 1; position <= k; position++) {
            if (clickedPositions.contains(position)) {
                dcg += 1.0 / log2(position + 1);
            }
        }
        double idcg = 0;
        for (int position = 1; position <= Math.min(hits, k); position++) {
            idcg += 1.0 / log2(position + 1);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double log2(int value) {
        return Math.log(value) / LN2;
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : round4(numerator / (double) denominator);
    }

    private double average(double sum, int count) {
        return count <= 0 ? 0 : round4(sum / count);
    }

    private long nz(Long value) {
        return value == null ? 0 : value;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record TraceKey(String traceId, Long userId, String algorithm) {
    }
}
