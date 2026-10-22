CREATE TABLE category (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50)     NOT NULL COMMENT '分类名',
    sort_order INT             NOT NULL DEFAULT 0 COMMENT '越小越靠前',
    status     TINYINT         NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '商品分类';

INSERT INTO category (name, sort_order) VALUES
    ('秒杀券', 10),
    ('软件', 20),
    ('实物', 30);

CREATE TABLE product (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    category_id   BIGINT UNSIGNED NOT NULL,
    name          VARCHAR(120)    NOT NULL COMMENT '商品名',
    description   TEXT            NOT NULL COMMENT '商品描述',
    price         DECIMAL(10, 2)  NOT NULL COMMENT '售价，单位元',
    cover_file_id BIGINT UNSIGNED NULL COMMENT '封面图 file_object.id',
    status        VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ON_SALE/OFF_SHELF',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_product_status_category (status, category_id, id),
    KEY idx_product_category (category_id, id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT fk_product_cover FOREIGN KEY (cover_file_id) REFERENCES file_object (id) ON DELETE SET NULL,
    CONSTRAINT chk_product_price CHECK (price >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '商品';
