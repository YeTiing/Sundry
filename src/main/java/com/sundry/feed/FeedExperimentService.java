package com.sundry.feed;

import com.sundry.feed.dto.FeedExperimentView;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 稳定实验分流：同一 userId 永远在同一组；对照组走规则排序，不触发 AI 构建。
 */
@Service
public class FeedExperimentService {

    private static final double Z = 1.96;

    private final FeedProperties properties;
    private final FeedMetricsMapper mapper;

    public FeedExperimentService(FeedProperties properties, FeedMetricsMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public FeedExperimentView report(int days) {
        int window = Math.min(Math.max(days, 1), 90);
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        long controlExposures = 0;
        long controlClicks = 0;
        long aiExposures = 0;
        long aiClicks = 0;
        List<FeedMetricsMapper.ExperimentCount> rows = mapper.countExperimentEvents(since);
        for (FeedMetricsMapper.ExperimentCount row : rows) {
            long exposures = row.getExposures() == null ? 0 : row.getExposures();
            long clicks = row.getClicks() == null ? 0 : row.getClicks();
            if ("CONTROL".equals(row.getGrp())) {
                controlExposures = exposures;
                controlClicks = clicks;
            } else if ("AI".equals(row.getGrp())) {
                aiExposures = exposures;
                aiClicks = clicks;
            }
        }
        Wilson control = wilson(controlClicks, controlExposures);
        Wilson ai = wilson(aiClicks, aiExposures);
        Double lift = control.ctr() > 0 && ai.ctr() > 0
                ? round(ai.ctr() / control.ctr() - 1)
                : null;
        return new FeedExperimentView(window, properties.getExperimentControlPercent(),
                side(control), side(ai), lift);
    }

    private FeedExperimentView.Side side(Wilson value) {
        return new FeedExperimentView.Side(value.exposures(), value.clicks(),
                value.ctr(), value.lower(), value.upper());
    }

    public boolean isControl(Long userId) {
        return properties.isExperimentEnabled() && userId != null
                && bucket(userId) < properties.getExperimentControlPercent();
    }

    /**
     * 稳定哈希到 0..99，和日期无关；调整分组只影响实验配置，不影响同一批用户的一致性。
     */
    public int bucket(Long userId) {
        long x = userId == null ? 0 : userId;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return (int) Math.floorMod(x, 100);
    }

    /**
     * 对照组用固定前缀 + 用户 + 日期作为 buildId，前端曝光/点击会带上它。
     */
    public String controlBuildId(Long userId) {
        return "RULE:" + userId + ":" + LocalDate.now().toEpochDay();
    }

    public Wilson wilson(long clicks, long exposures) {
        if (exposures <= 0) {
            return new Wilson(0, 0, 0, 0, 0);
        }
        double n = exposures;
        double p = clicks / n;
        double denominator = 1 + Z * Z / n;
        double center = (p + Z * Z / (2 * n)) / denominator;
        double margin = Z * Math.sqrt(p * (1 - p) / n + Z * Z / (4 * n * n)) / denominator;
        return new Wilson(round(p), round(Math.max(0, center - margin)),
                round(Math.min(1, center + margin)), exposures, clicks);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Wilson(double ctr, double lower, double upper,
                         long exposures, long clicks) {
    }
}
