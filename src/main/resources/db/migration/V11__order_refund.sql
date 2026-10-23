ALTER TABLE orders
    ADD COLUMN refunded_at   DATETIME(3)   NULL AFTER cancelled_at,
    ADD COLUMN refund_amount DECIMAL(10, 2) NULL AFTER refunded_at;

CREATE TABLE order_refund (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id      BIGINT UNSIGNED NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    amount        DECIMAL(10, 2)  NOT NULL COMMENT '申请退款金额，本版为整单全额',
    reason        VARCHAR(200)    NOT NULL COMMENT '用户申请理由',
    status        VARCHAR(20)     NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN',
    prev_status   VARCHAR(20)     NOT NULL COMMENT '申请前的订单状态',
    applied_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at   DATETIME(3)     NULL,
    reviewer_id   BIGINT UNSIGNED NULL,
    review_remark VARCHAR(200)    NULL COMMENT '审核意见：拒绝理由或通过备注',
    PRIMARY KEY (id),
    KEY idx_order_refund_order (order_id, id),
    KEY idx_order_refund_status (status, id),
    CONSTRAINT fk_order_refund_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_refund_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '退款申请';
