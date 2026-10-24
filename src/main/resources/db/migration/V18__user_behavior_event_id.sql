-- 行为异步落库：用 event_id 去重，重试时 INSERT IGNORE
ALTER TABLE user_behavior
    ADD COLUMN event_id VARCHAR(64) NULL AFTER id,
    ADD UNIQUE KEY uk_behavior_event (event_id);
