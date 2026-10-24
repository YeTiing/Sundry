package com.sundry.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.feed")
public class FeedProperties {

    private int behaviorBatchSize = 500;

    public int getBehaviorBatchSize() {
        return behaviorBatchSize;
    }

    public void setBehaviorBatchSize(int behaviorBatchSize) {
        this.behaviorBatchSize = behaviorBatchSize;
    }
}
