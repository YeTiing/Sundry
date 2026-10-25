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
    private Duration feedCacheTtl = Duration.ofMinutes(10);
    private Duration feedPendingTtl = Duration.ofMinutes(2);
    private Duration feedVariantTtl = Duration.ofMinutes(20);
    private int feedVariantCount = 2;
    private int feedVariantDedupHead = 6;
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

    public Duration getFeedCacheTtl() {
        return feedCacheTtl;
    }

    public void setFeedCacheTtl(Duration feedCacheTtl) {
        this.feedCacheTtl = feedCacheTtl;
    }

    public Duration getFeedPendingTtl() {
        return feedPendingTtl;
    }

    public void setFeedPendingTtl(Duration feedPendingTtl) {
        this.feedPendingTtl = feedPendingTtl;
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
