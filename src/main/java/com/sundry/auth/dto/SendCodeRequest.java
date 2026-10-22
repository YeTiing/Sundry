package com.sundry.auth.dto;

import com.sundry.auth.sms.SmsScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 发码请求。record 是 Java 16+ 的不可变数据载体，自动生成构造器和访问器。
 * 校验注解由 Controller 参数上的 @Valid 触发，失败时抛 MethodArgumentNotValidException。
 */
public record SendCodeRequest(
        @NotBlank(message = "手机号不能为空")              // 不能为 null、空串或纯空白
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") // 大陆 11 位手机号
        String phone,

        @NotNull(message = "验证码场景不能为空")           // 枚举字段只要求非 null
        SmsScene scene) {
}
