package com.sundry.shop;

/**
 * 公开的店铺名片。不包含管理员手机号等敏感信息。
 */
public record ShopView(String shopName, String logoUrl, Long logoFileId,
                       String description, String contact, String shippingAddress) {
}
