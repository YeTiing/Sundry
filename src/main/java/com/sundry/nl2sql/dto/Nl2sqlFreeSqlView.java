package com.sundry.nl2sql.dto;

import java.util.List;
import java.util.Map;

public record Nl2sqlFreeSqlView(String sql, List<Column> columns,
                                 List<Map<String, Object>> rows,
                                 String traceId, int rowCount, long latencyMs) {

    public record Column(String key, String label) {
    }
}
