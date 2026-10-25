package com.sundry.assistant;

import java.util.List;
import java.util.Map;

public record AssistantLlmRequest(String message, List<Map<String, String>> history,
                                  String role, List<AssistantToolSpec> tools) {
}
