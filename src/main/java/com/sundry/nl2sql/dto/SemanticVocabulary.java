package com.sundry.nl2sql.dto;

import java.util.List;

/**
 * 发给模型的词汇表：只有业务语义，不含表名、列名和 SQL。
 */
public record SemanticVocabulary(List<MetricView> metrics,
                                 List<DimensionView> dimensions,
                                 List<FilterView> filters,
                                 List<String> timePresets,
                                 LimitsView limits) {

    public record MetricView(String key, String label, List<String> synonyms, String unit) {
    }

    public record DimensionView(String key, String label, List<String> synonyms,
                                List<String> granularity) {
    }

    public record FilterView(String key, String label, String type, List<String> values) {
    }

    public record LimitsView(int maxMetrics, int maxDimensions, int maxRangeDays, int maxRows) {
    }
}
