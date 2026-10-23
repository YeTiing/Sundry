package com.sundry.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRequest(
        @NotBlank(message = "退款原因不能为空")
        @Size(max = 200, message = "退款原因最多 200 个字符")
        String reason) {
}
