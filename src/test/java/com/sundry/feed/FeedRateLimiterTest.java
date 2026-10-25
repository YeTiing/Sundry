package com.sundry.feed;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedRateLimiterTest {

    @Test
    void limitsPerUser() {
        FeedRateLimiter limiter = limiter(2, 10);

        limiter.check(1L, "10.0.0.1");
        limiter.check(1L, "10.0.0.1");

        assertThatThrownBy(() -> limiter.check(1L, "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void limitsAnonymousByIp() {
        FeedRateLimiter limiter = limiter(10, 1);

        limiter.check(null, "10.0.0.2");
        assertThatThrownBy(() -> limiter.check(null, "10.0.0.2"))
                .isInstanceOf(BusinessException.class);

        // 另一个 IP 不受影响
        limiter.check(null, "10.0.0.3");
    }

    private FeedRateLimiter limiter(int userLimit, int ipLimit) {
        FeedProperties properties = new FeedProperties();
        properties.setRateLimitUserPerMinute(userLimit);
        properties.setRateLimitIpPerMinute(ipLimit);
        return new FeedRateLimiter(properties);
    }
}
