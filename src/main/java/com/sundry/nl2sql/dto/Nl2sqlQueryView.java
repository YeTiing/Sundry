package com.sundry.nl2sql.dto;

import java.util.List;
import java.util.Map;

public record Nl2sqlQueryView(String answer, List<Column> columns,
                              List<Map<String, Object>> rows, Map<String, Object> plan,
                              String traceId, int rowCount, long latencyMs) {

    public record Column(String key, String label, String unit) {
    }
}
