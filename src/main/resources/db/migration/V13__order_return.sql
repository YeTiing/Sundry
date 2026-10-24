ALTER TABLE order_refund
    ADD COLUMN kind               VARCHAR(20) NOT NULL DEFAULT 'REFUND'
        COMMENT 'REFUND=发货前退款，RETURN=退货退款' AFTER reason,
    ADD COLUMN return_tracking_no VARCHAR(64) NULL COMMENT '用户寄回的快递单号' AFTER review_remark,
    ADD COLUMN return_shipped_at  DATETIME(3) NULL COMMENT '用户寄回时间' AFTER return_tracking_no,
    ADD COLUMN received_at        DATETIME(3) NULL COMMENT '商家确认收到退货时间' AFTER return_shipped_at;
