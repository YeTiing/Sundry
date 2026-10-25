package com.sundry.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.feed")
public class FeedProperties {

    private int behaviorBatchSize = 500;
    private int behaviorRetentionDays = 30;
    private int behaviorCleanupBatchSize = 5000;
    private int behaviorCleanupMaxBatches = 10;

    private String eventTopic = "sundry.feed.event";
    private String eventGroup = "sundry-feed-event";

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
}
