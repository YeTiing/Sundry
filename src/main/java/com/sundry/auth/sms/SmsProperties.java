package com.sundry.auth.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 短信配置。@ConfigurationProperties(prefix="app.sms") 把 yml 中 app.sms.* 按名称绑定到字段；
 * @Component 让它成为 Bean。Duration 类型支持 5m、60s 这种写法。
 */
@Component
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

    private String provider = "log";
    private Duration codeTtl = Duration.ofMinutes(5);    // 验证码有效期
    private Duration cooldown = Duration.ofSeconds(60);  // 同一手机号的最小重发间隔
    private Duration attemptTtl = Duration.ofMinutes(5); // 错误次数计数的窗口
    private int maxAttempts = 5;                         // 允许输错的次数
    private int dailyLimit = 10;                         // 单手机号每日发送上限

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    public Duration getCooldown() {
        return cooldown;
    }

    public void setCooldown(Duration cooldown) {
        this.cooldown = cooldown;
    }

    public Duration getAttemptTtl() {
        return attemptTtl;
    }

    public void setAttemptTtl(Duration attemptTtl) {
        this.attemptTtl = attemptTtl;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }
}
