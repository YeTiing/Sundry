package com.sundry.ai;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Redis 中的 AI 推流快照。buildId 用于防止旧任务覆盖新结果。
 */
public record FeedSnapshot(String buildId, LocalDateTime builtAt, String model,
                           String promptVersion, List<SnapshotItem> items) {

    public record SnapshotItem(Long id, String reason) {
    }
}
