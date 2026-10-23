package com.sundry.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRejectRequest(
        @NotBlank(message = "拒绝理由不能为空")
        @Size(max = 200, message = "拒绝理由最多 200 个字符")
        String reason) {
}
