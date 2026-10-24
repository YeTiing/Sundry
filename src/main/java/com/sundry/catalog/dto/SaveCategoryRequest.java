package com.sundry.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增/编辑分类。status 为空时新增默认启用、编辑时保持原值。
 */
public record SaveCategoryRequest(
        @NotBlank(message = "分类名称不能为空")
        @Size(max = 50, message = "分类名称最多 50 个字符")
        String name,

        @Min(value = 0, message = "排序值不能小于 0")
        Integer sortOrder,

        @Min(value = 0, message = "状态只能是 0 或 1")
        @Max(value = 1, message = "状态只能是 0 或 1")
        Integer status) {
}
