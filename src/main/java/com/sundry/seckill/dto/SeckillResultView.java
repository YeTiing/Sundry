package com.sundry.seckill.dto;

public record SeckillResultView(String requestId, String status, String orderNo,
                                String errorCode, String errorMessage) {
}
