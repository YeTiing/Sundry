package com.sundry.auth.service;

import com.sundry.auth.sms.SmsProperties;
import com.sundry.auth.sms.SmsScene;
import com.sundry.auth.sms.SmsSender;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 验证码的生成、限流和校验。@Service 注册为 Bean；StringRedisTemplate 由 Spring Boot 自动配置。
 */
@Service
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // 四类 key 分开存：验证码、错误次数、发送冷却、每日计数
    private static final String CODE_KEY = "auth:sms:code:%s:%s";
    private static final String ATTEMPT_KEY = "auth:sms:attempt:%s:%s";
    private static final String COOLDOWN_KEY = "auth:sms:cooldown:%s";
    private static final String DAILY_KEY = "auth:sms:daily:%s:%s";

    /**
     * Lua 在 Redis 内原子执行，避免“先 GET 再 DEL”之间被另一个请求插入。
     * 返回值：1=成功，0=不存在/已过期，-1=不匹配，-2=尝试次数超限。
     */
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local attempts = tonumber(redis.call('GET', KEYS[2]) or '0')
            if attempts >= tonumber(ARGV[3]) then
                return -2
            end
            local stored = redis.call('GET', KEYS[1])
            if not stored then
                return 0
            end
            if stored == ARGV[1] then
                redis.call('DEL', KEYS[1])
                redis.call('DEL', KEYS[2])
                return 1
            end
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return -1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SmsSender smsSender;
    private final SmsProperties properties;

    public SmsCodeService(StringRedisTemplate redis, SmsSender smsSender, SmsProperties properties) {
        this.redis = redis;
        this.smsSender = smsSender;
        this.properties = properties;
    }

    public void send(String phone, SmsScene scene) {
        // setIfAbsent 等价于 Redis SETNX：只有 key 不存在才成功，天然实现 60 秒冷却
        String cooldownKey = COOLDOWN_KEY.formatted(phone);
        Boolean acquired = redis.opsForValue().setIfAbsent(cooldownKey, "1", properties.getCooldown());
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        // 每日计数：第一次 incr 后设置 24 小时过期
        String dailyKey = DAILY_KEY.formatted(phone, LocalDate.now(ZONE));
        Long count = redis.opsForValue().increment(dailyKey);
        if (count != null && count == 1L) {
            redis.expire(dailyKey, Duration.ofDays(1));
        }
        if (count != null && count > properties.getDailyLimit()) {
            throw new BusinessException(ErrorCode.SMS_DAILY_LIMIT);
        }

        // SecureRandom 生成 6 位数字，允许前导零
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeKey = CODE_KEY.formatted(scene, phone);
        redis.opsForValue().set(codeKey, code, properties.getCodeTtl());
        redis.delete(ATTEMPT_KEY.formatted(scene, phone));

        try {
            smsSender.send(phone, code, scene);
        } catch (RuntimeException ex) {
            // 发送失败就回滚验证码和冷却，让用户可以立刻重试
            redis.delete(codeKey);
            redis.delete(cooldownKey);
            log.error("failed to send sms, phone={}, scene={}", phone, scene, ex);
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    public void verifyAndConsume(String phone, SmsScene scene, String code) {
        // 两个 key：验证码、错误次数；后三个参数：用户输入、计数窗口秒数、最大错误次数
        Long result = redis.execute(
                VERIFY_SCRIPT,
                List.of(CODE_KEY.formatted(scene, phone), ATTEMPT_KEY.formatted(scene, phone)),
                code,
                String.valueOf(properties.getAttemptTtl().toSeconds()),
                String.valueOf(properties.getMaxAttempts()));

        if (result == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        switch (result.intValue()) {
            case 1 -> {
                return;
            }
            case 0 -> throw new BusinessException(ErrorCode.SMS_CODE_EXPIRED);
            case -1 -> throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
            case -2 -> throw new BusinessException(ErrorCode.SMS_CODE_ATTEMPTS_EXCEEDED);
            default -> throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
