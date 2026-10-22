package com.sundry.catalog.dto;

import com.sundry.catalog.ProductStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 上下架请求。
 */
public record ChangeProductStatusRequest(
        @NotNull(message = "状态不能为空")
        ProductStatus status) {
}
