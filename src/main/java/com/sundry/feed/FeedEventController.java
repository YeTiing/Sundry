package com.sundry.feed;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.feed.dto.FeedEventRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 曝光/点击回流。只接受登录用户，user_id 永远来自 JWT。
 */
@RestController
@RequestMapping("/api/feed/events")
public class FeedEventController {

    private final FeedEventPublisher feedEventPublisher;
    private final FeedRateLimiter rateLimiter;

    public FeedEventController(FeedEventPublisher feedEventPublisher,
                               FeedRateLimiter rateLimiter) {
        this.feedEventPublisher = feedEventPublisher;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@Valid @RequestBody FeedEventRequest request,
                       @AuthenticationPrincipal AuthenticatedUser user,
                       HttpServletRequest httpRequest) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        rateLimiter.check(user.id(), httpRequest.getRemoteAddr());
        feedEventPublisher.publish(user.id(), request);
    }
}
