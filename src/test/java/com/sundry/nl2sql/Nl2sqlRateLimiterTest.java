package com.sundry.nl2sql;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 滑动窗口限流：单用户 10 次/分钟、用户隔离、key 有 TTL。
 */
@SpringBootTest
@ActiveProfiles("test")
class Nl2sqlRateLimiterTest {

    private static final long USER_A = 9_100_001L;
    private static final long USER_B = 9_100_002L;

    @Autowired
    private Nl2sqlRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        clear();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void allowsTenRequestsThenRejectsEleventh() {
        for (int i = 0; i < 10; i++) {
            limiter.check(USER_A);
        }

        assertThatThrownBy(() -> limiter.check(USER_A))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void otherUsersAreNotAffected() {
        for (int i = 0; i < 10; i++) {
            limiter.check(USER_A);
        }

        limiter.check(USER_B);
    }

    @Test
    void windowKeyHasTtl() {
        limiter.check(USER_A);

        Long ttl = redis.getExpire("nl2sql:rate:" + USER_A);
        assertThat(ttl).isBetween(1L, 120L);
    }

    private void clear() {
        Set<String> keys = redis.keys("nl2sql:rate:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
