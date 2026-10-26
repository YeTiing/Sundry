package com.sundry.nl2sql.dto;

import java.time.LocalDateTime;

public record Nl2sqlLogView(Long id, Long userId, String role, String sessionId,
                            String question, int rowCount, int latencyMs, String status,
                            String rejectReason, String traceId, LocalDateTime createdAt) {
}
