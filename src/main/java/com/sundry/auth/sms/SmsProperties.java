package com.sundry.auth.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

    private String provider = "log";
    private Duration codeTtl = Duration.ofMinutes(5);
    private Duration cooldown = Duration.ofSeconds(60);
    private Duration attemptTtl = Duration.ofMinutes(5);
    private int maxAttempts = 5;
    private int dailyLimit = 10;

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
