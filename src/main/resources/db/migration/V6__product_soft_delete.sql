ALTER TABLE product
    ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '软删除时间' AFTER status,
    ADD KEY idx_product_deleted (deleted_at, status, id);
