package com.sundry.nl2sql;

import com.sundry.nl2sql.dto.SemanticVocabulary;

import java.util.List;
import java.util.Map;

public record SemanticLayer(int version, Base base,
                            Map<String, Metric> metrics,
                            Map<String, Dimension> dimensions,
                            Map<String, Filter> filters,
                            Map<String, String> conditionalJoins,
                            List<String> timePresets,
                            Limits limits) {

    public record Base(String fromTable, String fromAlias, String timeColumn,
                       List<Join> joins, List<String> where) {
    }

    public record Join(String table, String alias, String on, boolean itemLevel) {
    }

    public record Metric(String key, String label, List<String> synonyms, String unit,
                         String expr, String conditionalJoin, boolean itemLevel) {
    }

    public record Dimension(String key, String label, List<String> synonyms,
                            String column, List<String> granularity, boolean itemLevel) {
    }

    public record Filter(String key, String label, String column, String type,
                         List<String> values, boolean itemLevel) {
    }

    public record Limits(int maxMetrics, int maxDimensions, int maxRangeDays, int maxRows) {
    }

    public SemanticVocabulary vocabulary() {
        List<SemanticVocabulary.MetricView> metricViews = metrics.values().stream()
                .map(metric -> new SemanticVocabulary.MetricView(
                        metric.key(), metric.label(), metric.synonyms(), metric.unit()))
                .toList();
        List<SemanticVocabulary.DimensionView> dimensionViews = dimensions.values().stream()
                .map(dimension -> new SemanticVocabulary.DimensionView(
                        dimension.key(), dimension.label(), dimension.synonyms(),
                        dimension.granularity()))
                .toList();
        List<SemanticVocabulary.FilterView> filterViews = filters.values().stream()
                .map(filter -> new SemanticVocabulary.FilterView(
                        filter.key(), filter.label(), filter.type(), filter.values()))
                .toList();
        SemanticVocabulary.LimitsView limitsView = new SemanticVocabulary.LimitsView(
                limits.maxMetrics(), limits.maxDimensions(), limits.maxRangeDays(), limits.maxRows());
        return new SemanticVocabulary(metricViews, dimensionViews, filterViews,
                timePresets, limitsView);
    }
}
