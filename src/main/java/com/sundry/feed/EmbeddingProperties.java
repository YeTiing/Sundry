package com.sundry.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    private boolean syncEnabled = true;
    private int syncBatchSize = 200;
    private int syncMaxBatches = 4;
    private int orphanBatchSize = 200;

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public int getSyncBatchSize() {
        return syncBatchSize;
    }

    public void setSyncBatchSize(int syncBatchSize) {
        this.syncBatchSize = syncBatchSize;
    }

    public int getSyncMaxBatches() {
        return syncMaxBatches;
    }

    public void setSyncMaxBatches(int syncMaxBatches) {
        this.syncMaxBatches = syncMaxBatches;
    }

    public int getOrphanBatchSize() {
        return orphanBatchSize;
    }

    public void setOrphanBatchSize(int orphanBatchSize) {
        this.orphanBatchSize = orphanBatchSize;
    }
}
