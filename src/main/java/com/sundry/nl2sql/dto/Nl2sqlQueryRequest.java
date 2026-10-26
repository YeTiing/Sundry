package com.sundry.nl2sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Nl2sqlQueryRequest(
        @NotBlank @Size(max = 500) String question,
        @Size(max = 64) String sessionId) {
}
