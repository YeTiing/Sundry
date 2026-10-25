ALTER TABLE product
    ADD COLUMN tags VARCHAR(255) NOT NULL DEFAULT ''
        COMMENT '逗号分隔，最多 8 个' AFTER description;
