package com.sundry.shop;

import java.time.LocalDateTime;

/**
 * shop_profile 单行记录。logo 的 bucket/key 来自联表查询，不对应表字段。
 */
public class ShopProfile {

    private Long id;
    private String shopName;
    private Long logoFileId;
    private String description;
    private String contact;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String logoBucket;
    private String logoObjectKey;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public Long getLogoFileId() {
        return logoFileId;
    }

    public void setLogoFileId(Long logoFileId) {
        this.logoFileId = logoFileId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getLogoBucket() {
        return logoBucket;
    }

    public void setLogoBucket(String logoBucket) {
        this.logoBucket = logoBucket;
    }

    public String getLogoObjectKey() {
        return logoObjectKey;
    }

    public void setLogoObjectKey(String logoObjectKey) {
        this.logoObjectKey = logoObjectKey;
    }
}
