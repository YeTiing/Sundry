ALTER TABLE product
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '创建幂等键' AFTER id,
    ADD UNIQUE KEY uk_product_request_id (request_id);
