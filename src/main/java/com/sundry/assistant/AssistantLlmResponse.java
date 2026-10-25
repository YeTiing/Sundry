package com.sundry.assistant;

import java.util.List;

public record AssistantLlmResponse(List<ToolCall> toolCalls, String content,
                                   String model, String promptVersion) {

    public record ToolCall(String id, String name, String arguments) {
    }
}
