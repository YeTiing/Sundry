package com.sundry.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 店铺名片编辑，仅超级管理员可用。
 */
public record UpdateShopRequest(
        @NotBlank(message = "店名不能为空")
        @Size(max = 100, message = "店名不能超过 100 个字符")
        String shopName,

        @Size(max = 500, message = "店铺简介不能超过 500 个字符")
        String description,

        @Size(max = 200, message = "联系方式不能超过 200 个字符")
        String contact,

        Long logoFileId) {
}
