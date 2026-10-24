package com.sundry.catalog.dto;

import com.sundry.catalog.Category;

/**
 * 后台分类列表项，带商品引用数。
 */
public record AdminCategoryView(Long id, String name, int sortOrder, int status, long productCount) {

    public static AdminCategoryView from(Category category, long productCount) {
        return new AdminCategoryView(category.getId(), category.getName(),
                category.getSortOrder() == null ? 0 : category.getSortOrder(),
                category.getStatus() == null ? 1 : category.getStatus(),
                productCount);
    }
}
