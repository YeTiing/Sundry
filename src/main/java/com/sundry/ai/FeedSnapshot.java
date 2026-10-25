package com.sundry.ai;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Redis 中的 AI 推流快照。buildId 用于防止旧任务覆盖新结果。
 */
public record FeedSnapshot(String buildId, LocalDateTime builtAt, String model,
                           String promptVersion, List<SnapshotItem> items,
                           String strategy) {

    public static final String RULE_FALLBACK_MODEL = "rule-fallback";

    public boolean ruleFallback() {
        return RULE_FALLBACK_MODEL.equals(model);
    }

    /**
     * 兼容旧快照（没有策略字段）。
     */
    public FeedSnapshot(String buildId, LocalDateTime builtAt, String model,
                        String promptVersion, List<SnapshotItem> items) {
        this(buildId, builtAt, model, promptVersion, items, null);
    }

    public record SnapshotItem(Long id, String reason) {
    }
}
