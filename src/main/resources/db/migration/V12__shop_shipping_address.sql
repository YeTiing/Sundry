ALTER TABLE shop_profile
    ADD COLUMN shipping_address VARCHAR(300) NULL COMMENT '店铺发货/退货地址' AFTER contact;
