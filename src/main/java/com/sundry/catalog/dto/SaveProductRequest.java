package com.sundry.catalog.dto;

import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 新建 / 编辑商品的请求。价格最大 8 位整数、2 位小数，单位元。
 */
public record SaveProductRequest(
        @NotBlank(message = "商品名称不能为空")
        @Size(max = 120, message = "商品名称最多 120 字")
        String name,

        @NotBlank(message = "商品描述不能为空")
        @Size(max = 5000, message = "商品描述最多 5000 字")
        String description,

        @NotNull(message = "价格不能为空")
        @DecimalMin(value = "0.00", message = "价格不能为负")
        @Digits(integer = 8, fraction = 2, message = "价格最多 8 位整数和 2 位小数")
        BigDecimal price,

        @NotNull(message = "分类不能为空")
        Long categoryId,

        Long coverFileId,

        // 不传默认 PHYSICAL；DIGITAL 必须绑定 digitalFileId
        ProductType type,

        Long digitalFileId,

        // 只在新建时生效；编辑时忽略，状态走单独的上下架接口
        ProductStatus status) {
}
