package com.sundry.catalog.dto;

import com.sundry.catalog.Category;

/**
 * 分类展示项。
 */
public record CategoryView(Long id, String name, int sortOrder) {

    public static CategoryView from(Category category) {
        return new CategoryView(category.getId(), category.getName(),
                category.getSortOrder() == null ? 0 : category.getSortOrder());
    }
}
