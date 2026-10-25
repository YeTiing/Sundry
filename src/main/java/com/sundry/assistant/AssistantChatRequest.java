package com.sundry.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistantChatRequest(
        @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 500) String message) {
}
