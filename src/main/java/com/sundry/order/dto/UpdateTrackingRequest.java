package com.sundry.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTrackingRequest(
        @NotBlank(message = "快递单号不能为空")
        @Size(max = 64, message = "快递单号最多 64 个字符")
        String trackingNo) {
}
