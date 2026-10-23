package com.sundry.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 用户名片编辑：昵称和简介都可以为空。
 */
public record UpdateProfileRequest(
        @Size(max = 50, message = "昵称不能超过 50 个字符")
        String nickname,

        @Size(max = 200, message = "简介不能超过 200 个字符")
        String bio) {
}
