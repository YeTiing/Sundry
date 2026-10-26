package com.sundry.nl2sql;

import java.time.LocalDateTime;
import java.util.List;

public record ValidatedPlan(List<Metric> metrics, List<Dimension> dimensions,
                            List<FilterBinding> filters, TimeRange time,
                            List<SortBinding> sort, int limit,
                            List<String> conditionalJoins) {

    public record Metric(String key, String label, String unit, String expr) {
    }

    public record Dimension(String key, String label, String expression) {
    }

    public record FilterBinding(String key, String column, Object value) {
    }

    public record SortBinding(String key, String order) {
    }

    public record TimeRange(LocalDateTime start, LocalDateTime end) {
    }
}
