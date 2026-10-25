package com.sundry.assistant;

import java.util.List;

public record RouteDefinition(String key, String label, String name,
                              List<String> synonyms, boolean requiresAuth,
                              boolean requiresAdmin) {
}
