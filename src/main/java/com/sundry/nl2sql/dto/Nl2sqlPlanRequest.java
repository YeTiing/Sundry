package com.sundry.nl2sql.dto;

import java.util.List;
import java.util.Map;

public record Nl2sqlPlanRequest(String question, String role,
                                SemanticVocabulary vocabulary,
                                List<Map<String, String>> history) {
}
