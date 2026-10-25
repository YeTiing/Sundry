package com.sundry.feed;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.feed.dto.FeedPage;
import com.sundry.feed.dto.FeedStatusView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页推荐 Feed。未登录时返回匿名 Feed；登录用户的 AI 排序由后台异步构建。
 */
@RestController
@RequestMapping("/api/feed")
@Validated
public class FeedController {

    private final FeedService feedService;
    private final FeedRateLimiter rateLimiter;

    public FeedController(FeedService feedService, FeedRateLimiter rateLimiter) {
        this.feedService = feedService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public FeedPage feed(
            @RequestParam(required = false) @Min(0) Long cursor,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(required = false) String buildId,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request) {
        Long userId = user == null ? null : user.id();
        rateLimiter.check(userId, request.getRemoteAddr());
        return feedService.feed(userId, cursor, size, refresh, buildId);
    }

    @GetMapping("/status")
    public FeedStatusView status(@AuthenticationPrincipal AuthenticatedUser user,
                                 HttpServletRequest request) {
        Long userId = user == null ? null : user.id();
        rateLimiter.check(userId, request.getRemoteAddr());
        return feedService.status(userId);
    }
}
