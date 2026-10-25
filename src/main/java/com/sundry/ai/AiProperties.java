package com.sundry.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 推流配置。密钥和内部令牌只从环境变量注入，不写进仓库。
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:8000";
    private String internalToken = "";
    private Duration timeout = Duration.ofSeconds(5);
    private int feedCandidateLimit = 60;
    private int feedRerankLimit = 20;
    private Duration feedQueueTtl = Duration.ofMinutes(30);
    private Duration feedVariantTtl = Duration.ofMinutes(20);
    private int feedVariantCount = 2;
    private int feedVariantDedupHead = 6;
    private Duration feedVariantDeadline = Duration.ofSeconds(12);
    private Duration feedBuildCooldown = Duration.ofSeconds(10);
    private int feedBuildRateLimit = 20;
    private int feedBuildConcurrency = 2;
    private int feedBatchSize = 5;
    private int feedQueueMax = 1000;
    private int maxAttempts = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getFeedCandidateLimit() {
        return feedCandidateLimit;
    }

    public void setFeedCandidateLimit(int feedCandidateLimit) {
        this.feedCandidateLimit = feedCandidateLimit;
    }

    public int getFeedRerankLimit() {
        return feedRerankLimit;
    }

    public void setFeedRerankLimit(int feedRerankLimit) {
        this.feedRerankLimit = feedRerankLimit;
    }

    public Duration getFeedQueueTtl() {
        return feedQueueTtl;
    }

    public void setFeedQueueTtl(Duration feedQueueTtl) {
        this.feedQueueTtl = feedQueueTtl;
    }

    public int getFeedBuildConcurrency() {
        return feedBuildConcurrency;
    }

    public void setFeedBuildConcurrency(int feedBuildConcurrency) {
        this.feedBuildConcurrency = feedBuildConcurrency;
    }

    public Duration getFeedVariantTtl() {
        return feedVariantTtl;
    }

    public void setFeedVariantTtl(Duration feedVariantTtl) {
        this.feedVariantTtl = feedVariantTtl;
    }

    public int getFeedVariantCount() {
        return feedVariantCount;
    }

    public void setFeedVariantCount(int feedVariantCount) {
        this.feedVariantCount = feedVariantCount;
    }

    public int getFeedVariantDedupHead() {
        return feedVariantDedupHead;
    }

    public void setFeedVariantDedupHead(int feedVariantDedupHead) {
        this.feedVariantDedupHead = feedVariantDedupHead;
    }

    /**
     * 当前只有 PREF / EXPLORE 两套策略，配置超过 2 也按 2 算。
     */
    public int getEffectiveFeedVariantCount() {
        return Math.min(Math.max(1, feedVariantCount), 2);
    }

    public Duration getFeedVariantDeadline() {
        return feedVariantDeadline;
    }

    public void setFeedVariantDeadline(Duration feedVariantDeadline) {
        this.feedVariantDeadline = feedVariantDeadline;
    }

    public Duration getFeedBuildCooldown() {
        return feedBuildCooldown;
    }

    public void setFeedBuildCooldown(Duration feedBuildCooldown) {
        this.feedBuildCooldown = feedBuildCooldown;
    }

    public int getFeedBuildRateLimit() {
        return feedBuildRateLimit;
    }

    public void setFeedBuildRateLimit(int feedBuildRateLimit) {
        this.feedBuildRateLimit = feedBuildRateLimit;
    }

    public int getFeedBatchSize() {
        return feedBatchSize;
    }

    public void setFeedBatchSize(int feedBatchSize) {
        this.feedBatchSize = feedBatchSize;
    }

    public int getFeedQueueMax() {
        return feedQueueMax;
    }

    public void setFeedQueueMax(int feedQueueMax) {
        this.feedQueueMax = feedQueueMax;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
