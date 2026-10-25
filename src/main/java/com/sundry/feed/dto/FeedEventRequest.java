package com.sundry.feed.dto;

import com.sundry.feed.FeedAlgorithm;
import com.sundry.feed.FeedEventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 前端批量上报曝光/点击。user_id 不在请求体里，由 JWT 决定。
 */
public record FeedEventRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9-]{8,64}", message = "traceId 格式不正确")
        String traceId,

        @Size(max = 64)
        String buildId,

        @NotNull
        FeedAlgorithm algorithm,

        @NotEmpty
        @Size(max = 100, message = "单次最多上报 100 条")
        @Valid
        List<EventItem> events) {

    public record EventItem(
            @NotNull
            FeedEventType type,

            @NotNull
            @Positive
            Long productId,

            @Min(1)
            @Max(500)
            int position) {
    }
}
