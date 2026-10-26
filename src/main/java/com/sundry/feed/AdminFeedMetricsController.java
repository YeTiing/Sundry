package com.sundry.feed;

import com.sundry.feed.dto.FeedExperimentView;
import com.sundry.feed.dto.FeedMetricsView;
import com.sundry.feed.dto.FeedReplayView;
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
    private final FeedReplayService feedReplayService;
    private final FeedExperimentService feedExperimentService;

    public AdminFeedMetricsController(FeedMetricsService feedMetricsService,
                                      FeedReplayService feedReplayService,
                                      FeedExperimentService feedExperimentService) {
        this.feedMetricsService = feedMetricsService;
        this.feedReplayService = feedReplayService;
        this.feedExperimentService = feedExperimentService;
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public FeedMetricsView metrics(
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return feedMetricsService.metrics(days);
    }

    @GetMapping("/replay")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public FeedReplayView replay(
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int k) {
        return feedReplayService.replay(days, k);
    }

    @GetMapping("/experiment")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public FeedExperimentView experiment(
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return feedExperimentService.report(days);
    }
}
