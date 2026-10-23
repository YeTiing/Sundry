ALTER TABLE app_user
    ADD COLUMN nickname VARCHAR(50) NULL COMMENT '昵称' AFTER phone,
    ADD COLUMN avatar_file_id BIGINT UNSIGNED NULL COMMENT '头像 file_object.id' AFTER nickname,
    ADD COLUMN bio VARCHAR(200) NULL COMMENT '个人简介' AFTER avatar_file_id,
    ADD KEY idx_app_user_avatar (avatar_file_id),
    ADD CONSTRAINT fk_app_user_avatar FOREIGN KEY (avatar_file_id) REFERENCES file_object (id) ON DELETE SET NULL;

CREATE TABLE shop_profile (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    shop_name     VARCHAR(100)    NOT NULL COMMENT '店名',
    logo_file_id  BIGINT UNSIGNED NULL COMMENT '店铺 Logo file_object.id',
    description   VARCHAR(500)    NULL COMMENT '店铺简介',
    contact       VARCHAR(200)    NULL COMMENT '联系方式',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_shop_logo FOREIGN KEY (logo_file_id) REFERENCES file_object (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '店铺资料（单商户只有一行）';

INSERT INTO shop_profile (id, shop_name, description)
VALUES (1, 'Sundry', '自营杂货。');
