package com.sundry.nl2sql.dto;

import java.util.List;

public record Nl2sqlPlanResponse(Plan plan, boolean unsupported, String reason,
                                 String model, String promptVersion) {

    public record Plan(List<String> metrics, List<String> dimensions,
                       List<Filter> filters, Time time, String granularity,
                       List<Sort> sort, Integer limit) {
        public record Filter(String key, Object value) {
        }

        public record Time(String preset) {
        }

        public record Sort(String metric, String order) {
        }
    }
}
