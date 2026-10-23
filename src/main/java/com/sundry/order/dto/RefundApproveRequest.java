package com.sundry.order.dto;

import jakarta.validation.constraints.Size;

public record RefundApproveRequest(
        @Size(max = 200, message = "审核备注最多 200 个字符")
        String remark) {
}
