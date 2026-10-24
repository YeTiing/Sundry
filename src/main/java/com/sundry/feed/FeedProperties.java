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
}
