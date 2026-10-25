package com.sundry.feed;

import com.sundry.feed.dto.FeedMetricsView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台离线评估。只读，不修改任何数据。
 */
@RestController
@RequestMapping("/api/admin/feed")
@Validated
public class AdminFeedMetricsController {

    private final FeedMetricsService feedMetricsService;

    public AdminFeedMetricsController(FeedMetricsService feedMetricsService) {
        this.feedMetricsService = feedMetricsService;
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public FeedMetricsView metrics(
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return feedMetricsService.metrics(days);
    }
}
