package com.sundry.cart;

import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购物车行对象；商品信息来自联表查询。
 */
public class CartItem {

    private Long id;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String productName;
    private ProductType productType;
    private BigDecimal price;
    private Integer stock;
    private ProductStatus productStatus;
    private LocalDateTime productDeletedAt;
    private String coverBucket;
    private String coverObjectKey;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
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

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public ProductType getProductType() {
        return productType;
    }

    public void setProductType(ProductType productType) {
        this.productType = productType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public ProductStatus getProductStatus() {
        return productStatus;
    }

    public void setProductStatus(ProductStatus productStatus) {
        this.productStatus = productStatus;
    }

    public LocalDateTime getProductDeletedAt() {
        return productDeletedAt;
    }

    public void setProductDeletedAt(LocalDateTime productDeletedAt) {
        this.productDeletedAt = productDeletedAt;
    }

    public String getCoverBucket() {
        return coverBucket;
    }

    public void setCoverBucket(String coverBucket) {
        this.coverBucket = coverBucket;
    }

    public String getCoverObjectKey() {
        return coverObjectKey;
    }

    public void setCoverObjectKey(String coverObjectKey) {
        this.coverObjectKey = coverObjectKey;
    }
}
