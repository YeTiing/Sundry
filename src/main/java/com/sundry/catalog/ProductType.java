package com.sundry.catalog;

/**
 * 实物商品需要发货；数字商品支付后可下载，不走库存和物流。
 */
public enum ProductType {
    PHYSICAL,
    DIGITAL
}
