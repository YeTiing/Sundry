package com.sundry.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.feed")
public class FeedProperties {

    private int behaviorBatchSize = 500;
    private int behaviorRetentionDays = 30;
    private int behaviorCleanupBatchSize = 5000;
    private int behaviorCleanupMaxBatches = 10;

    private String eventTopic = "sundry.feed.event";
    private String eventGroup = "sundry-feed-event";

    private int eventExposureRetentionDays = 14;
    private int eventClickRetentionDays = 90;
    private int eventCleanupBatchSize = 5000;
    private int eventCleanupMaxBatches = 10;

    private int recallHotLimit = 120;
    private int recallNewLimit = 60;
    private int recallCategoryLimit = 120;
    private int recallCoPurchaseLimit = 80;
    private int recallMinCandidates = 60;
    private int recallMaxCandidates = 500;
    private int recallHotDays = 30;
    private int recallSemanticLimit = 100;
    private int rateLimitUserPerMinute = 120;
    private int rateLimitIpPerMinute = 60;
    private int featuredLimit = 3;
    private Duration featuredCacheTtl = Duration.ofSeconds(10);

    public int getBehaviorBatchSize() {
        return behaviorBatchSize;
    }

    public void setBehaviorBatchSize(int behaviorBatchSize) {
        this.behaviorBatchSize = behaviorBatchSize;
    }

    public int getBehaviorRetentionDays() {
        return behaviorRetentionDays;
    }

    public void setBehaviorRetentionDays(int behaviorRetentionDays) {
        this.behaviorRetentionDays = behaviorRetentionDays;
    }

    public int getBehaviorCleanupBatchSize() {
        return behaviorCleanupBatchSize;
    }

    public void setBehaviorCleanupBatchSize(int behaviorCleanupBatchSize) {
        this.behaviorCleanupBatchSize = behaviorCleanupBatchSize;
    }

    public int getBehaviorCleanupMaxBatches() {
        return behaviorCleanupMaxBatches;
    }

    public void setBehaviorCleanupMaxBatches(int behaviorCleanupMaxBatches) {
        this.behaviorCleanupMaxBatches = behaviorCleanupMaxBatches;
    }

    public String getEventTopic() {
        return eventTopic;
    }

    public void setEventTopic(String eventTopic) {
        this.eventTopic = eventTopic;
    }

    public String getEventGroup() {
        return eventGroup;
    }

    public void setEventGroup(String eventGroup) {
        this.eventGroup = eventGroup;
    }

    public int getEventExposureRetentionDays() {
        return eventExposureRetentionDays;
    }

    public void setEventExposureRetentionDays(int eventExposureRetentionDays) {
        this.eventExposureRetentionDays = eventExposureRetentionDays;
    }

    public int getEventClickRetentionDays() {
        return eventClickRetentionDays;
    }

    public void setEventClickRetentionDays(int eventClickRetentionDays) {
        this.eventClickRetentionDays = eventClickRetentionDays;
    }

    public int getEventCleanupBatchSize() {
        return eventCleanupBatchSize;
    }

    public void setEventCleanupBatchSize(int eventCleanupBatchSize) {
        this.eventCleanupBatchSize = eventCleanupBatchSize;
    }

    public int getEventCleanupMaxBatches() {
        return eventCleanupMaxBatches;
    }

    public void setEventCleanupMaxBatches(int eventCleanupMaxBatches) {
        this.eventCleanupMaxBatches = eventCleanupMaxBatches;
    }

    public int getRecallHotLimit() {
        return recallHotLimit;
    }

    public void setRecallHotLimit(int recallHotLimit) {
        this.recallHotLimit = recallHotLimit;
    }

    public int getRecallNewLimit() {
        return recallNewLimit;
    }

    public void setRecallNewLimit(int recallNewLimit) {
        this.recallNewLimit = recallNewLimit;
    }

    public int getRecallCategoryLimit() {
        return recallCategoryLimit;
    }

    public void setRecallCategoryLimit(int recallCategoryLimit) {
        this.recallCategoryLimit = recallCategoryLimit;
    }

    public int getRecallCoPurchaseLimit() {
        return recallCoPurchaseLimit;
    }

    public void setRecallCoPurchaseLimit(int recallCoPurchaseLimit) {
        this.recallCoPurchaseLimit = recallCoPurchaseLimit;
    }

    public int getRecallMinCandidates() {
        return recallMinCandidates;
    }

    public void setRecallMinCandidates(int recallMinCandidates) {
        this.recallMinCandidates = recallMinCandidates;
    }

    public int getRecallMaxCandidates() {
        return recallMaxCandidates;
    }

    public void setRecallMaxCandidates(int recallMaxCandidates) {
        this.recallMaxCandidates = recallMaxCandidates;
    }

    public int getRecallHotDays() {
        return recallHotDays;
    }

    public void setRecallHotDays(int recallHotDays) {
        this.recallHotDays = recallHotDays;
    }

    public int getRecallSemanticLimit() {
        return recallSemanticLimit;
    }

    public void setRecallSemanticLimit(int recallSemanticLimit) {
        this.recallSemanticLimit = recallSemanticLimit;
    }

    public int getRateLimitUserPerMinute() {
        return rateLimitUserPerMinute;
    }

    public void setRateLimitUserPerMinute(int rateLimitUserPerMinute) {
        this.rateLimitUserPerMinute = rateLimitUserPerMinute;
    }

    public int getRateLimitIpPerMinute() {
        return rateLimitIpPerMinute;
    }

    public void setRateLimitIpPerMinute(int rateLimitIpPerMinute) {
        this.rateLimitIpPerMinute = rateLimitIpPerMinute;
    }

    public int getFeaturedLimit() {
        return featuredLimit;
    }

    public void setFeaturedLimit(int featuredLimit) {
        this.featuredLimit = featuredLimit;
    }

    public Duration getFeaturedCacheTtl() {
        return featuredCacheTtl;
    }

    public void setFeaturedCacheTtl(Duration featuredCacheTtl) {
        this.featuredCacheTtl = featuredCacheTtl;
    }
}
