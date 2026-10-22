package com.sundry.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 密码登录请求。登录时只校验非空，不套用注册的密码格式，避免老规则变更后无法登录。
 */
public record PasswordLoginRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "密码不能为空")
        String password) {
}
