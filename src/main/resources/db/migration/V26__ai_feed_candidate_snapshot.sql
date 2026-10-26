ALTER TABLE ai_feed_log
    ADD COLUMN candidate_snapshot JSON NULL
        COMMENT '候选集与特征快照，用于离线回放'
        AFTER fallback_reason;
