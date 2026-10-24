ALTER TABLE orders
    ADD COLUMN activity_id  BIGINT UNSIGNED NULL AFTER request_id,
    ADD COLUMN order_source VARCHAR(20) NOT NULL DEFAULT 'NORMAL' AFTER activity_id,
    ADD UNIQUE KEY uk_orders_activity_user (activity_id, user_id),
    ADD KEY idx_orders_activity (activity_id, status);
