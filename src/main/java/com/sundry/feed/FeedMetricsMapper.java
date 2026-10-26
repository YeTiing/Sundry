package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedMetricsMapper {

    Totals totals(@Param("since") LocalDateTime since);

    List<AlgorithmCount> countByAlgorithm(@Param("since") LocalDateTime since);

    List<StrategyCount> countByStrategy(@Param("since") LocalDateTime since);

    List<PositionCount> countByPosition(@Param("since") LocalDateTime since);

    long countDistinctExposedProducts(@Param("since") LocalDateTime since);

    long countOnSaleProducts();

    Double avgCategoriesPerTrace(@Param("since") LocalDateTime since);

    long countExposureTraces(@Param("since") LocalDateTime since);

    List<FeedMetricEvent> findEventsForNdcg(@Param("since") LocalDateTime since,
                                            @Param("k") int k);

    List<ReplaySnapshotRow> findReplaySnapshots(@Param("since") LocalDateTime since,
                                                @Param("limit") int limit);

    List<ReplayClickRow> findReplayClicks(@Param("buildIds") List<String> buildIds,
                                          @Param("since") LocalDateTime since);

    List<ExperimentCount> countExperimentEvents(@Param("since") LocalDateTime since);

    class Totals {
        private Long exposures;
        private Long clicks;

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }

        public Long getClicks() {
            return clicks;
        }

        public void setClicks(Long clicks) {
            this.clicks = clicks;
        }
    }

    class AlgorithmCount {
        private String algorithm;
        private Long exposures;
        private Long clicks;

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }

        public Long getClicks() {
            return clicks;
        }

        public void setClicks(Long clicks) {
            this.clicks = clicks;
        }
    }

    class StrategyCount {
        private String strategy;
        private Long exposures;
        private Long clicks;

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }

        public Long getClicks() {
            return clicks;
        }

        public void setClicks(Long clicks) {
            this.clicks = clicks;
        }
    }

    class ExperimentCount {
        private String grp;
        private Long exposures;
        private Long clicks;

        public String getGrp() {
            return grp;
        }

        public void setGrp(String grp) {
            this.grp = grp;
        }

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }

        public Long getClicks() {
            return clicks;
        }

        public void setClicks(Long clicks) {
            this.clicks = clicks;
        }
    }

    class ReplaySnapshotRow {
        private Long id;
        private Long userId;
        private LocalDateTime createdAt;
        private String candidateSnapshot;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public String getCandidateSnapshot() {
            return candidateSnapshot;
        }

        public void setCandidateSnapshot(String candidateSnapshot) {
            this.candidateSnapshot = candidateSnapshot;
        }
    }

    class ReplayClickRow {
        private Long id;
        private String buildId;
        private Long productId;
        private LocalDateTime createdAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getBuildId() {
            return buildId;
        }

        public void setBuildId(String buildId) {
            this.buildId = buildId;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }

    class PositionCount {
        private Integer position;
        private Long exposures;
        private Long clicks;

        public Integer getPosition() {
            return position;
        }

        public void setPosition(Integer position) {
            this.position = position;
        }

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }

        public Long getClicks() {
            return clicks;
        }

        public void setClicks(Long clicks) {
            this.clicks = clicks;
        }
    }
}
