package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedMetricsMapper {

    Totals totals(@Param("since") LocalDateTime since);

    List<AlgorithmCount> countByAlgorithm(@Param("since") LocalDateTime since);

    List<PositionCount> countByPosition(@Param("since") LocalDateTime since);

    long countDistinctExposedProducts(@Param("since") LocalDateTime since);

    long countOnSaleProducts();

    Double avgCategoriesPerTrace(@Param("since") LocalDateTime since);

    long countExposureTraces(@Param("since") LocalDateTime since);

    List<FeedMetricEvent> findEventsForNdcg(@Param("since") LocalDateTime since,
                                            @Param("k") int k);

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
