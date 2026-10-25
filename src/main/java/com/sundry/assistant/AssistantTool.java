package com.sundry.assistant;

import java.util.Map;

/**
 * OpenAI tools 形状；roles 不下发。
 */
public record AssistantTool(String type, Function function) {

    public record Function(String name, String description, Map<String, Object> parameters) {
    }
}
