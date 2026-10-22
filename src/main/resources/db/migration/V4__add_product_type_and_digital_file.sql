ALTER TABLE product
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'PHYSICAL' COMMENT 'PHYSICAL/DIGITAL' AFTER description,
    ADD COLUMN digital_file_id BIGINT UNSIGNED NULL COMMENT '数字商品文件 file_object.id' AFTER cover_file_id,
    ADD KEY idx_product_digital_file (digital_file_id),
    ADD CONSTRAINT fk_product_digital_file FOREIGN KEY (digital_file_id) REFERENCES file_object (id) ON DELETE SET NULL;
