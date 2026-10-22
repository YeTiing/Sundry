package com.sundry.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 注册请求。手机号、验证码、密码的格式都在进入业务层之前校验。
 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        String code,

        @NotBlank(message = "密码不能为空")
        // 正则要求 8-64 位，且至少有一个字母和一个数字
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$", message = "密码需 8-64 位且同时包含字母和数字")
        String password) {
}
