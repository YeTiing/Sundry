package com.sundry.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建/编辑收货地址。isDefault 为 null 表示保持原状态。
 */
public record AddressRequest(
        @NotBlank(message = "收货人不能为空")
        @Size(max = 50, message = "收货人最多 50 个字符")
        String receiverName,

        @NotBlank(message = "收货电话不能为空")
        @Size(max = 20, message = "收货电话最多 20 个字符")
        String receiverPhone,

        @NotBlank(message = "省份不能为空")
        @Size(max = 50, message = "省份最多 50 个字符")
        String province,

        @NotBlank(message = "城市不能为空")
        @Size(max = 50, message = "城市最多 50 个字符")
        String city,

        @NotBlank(message = "区县不能为空")
        @Size(max = 50, message = "区县最多 50 个字符")
        String district,

        @NotBlank(message = "详细地址不能为空")
        @Size(max = 200, message = "详细地址最多 200 个字符")
        String detail,

        Boolean isDefault) {
}
