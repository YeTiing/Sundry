package com.sundry.feed;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.catalog.dto.ProductView;
import com.sundry.common.api.ScrollPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页推荐 Feed。未登录时返回匿名 Feed。
 */
@RestController
@RequestMapping("/api/feed")
@Validated
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    public ScrollPage<ProductView> feed(
            @RequestParam(required = false) @Min(0) Long cursor,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return feedService.feed(user == null ? null : user.id(), cursor, size);
    }
}
