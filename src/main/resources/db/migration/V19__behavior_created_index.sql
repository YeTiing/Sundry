-- 浏览行为按时间清理：支持 (event_type, created_at) 范围删除
ALTER TABLE user_behavior
    ADD KEY idx_behavior_created (event_type, created_at);
