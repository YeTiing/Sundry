package com.sundry.feed;

import java.math.BigDecimal;

/**
 * 用户最近浏览/购买商品的价位区间，供 AI 偏好摘要使用。
 */
public class PriceRange {

    private BigDecimal minPrice;
    private BigDecimal maxPrice;

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(BigDecimal maxPrice) {
        this.maxPrice = maxPrice;
    }
}
