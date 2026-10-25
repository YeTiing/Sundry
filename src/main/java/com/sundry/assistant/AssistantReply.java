package com.sundry.assistant;

import java.util.Map;

public record AssistantReply(String action, String reply, String route,
                             String routeLabel, Map<String, String> query,
                             String traceId) {
}
