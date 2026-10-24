package com.sundry.feed.dto;

import jakarta.validation.constraints.NotNull;

public record ViewRequest(@NotNull(message = "商品不能为空") Long productId) {
}
