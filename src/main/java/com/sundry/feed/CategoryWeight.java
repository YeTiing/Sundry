package com.sundry.feed;

/**
 * 分类偏好权重：category_id + 行为次数/数量。
 */
public class CategoryWeight {

    private Long categoryId;
    private Long weight;

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getWeight() {
        return weight;
    }

    public void setWeight(Long weight) {
        this.weight = weight;
    }
}
