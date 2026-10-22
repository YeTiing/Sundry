package com.sundry.auth.dto;

import com.sundry.auth.sms.SmsScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SendCodeRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotNull(message = "验证码场景不能为空")
        SmsScene scene) {
}
