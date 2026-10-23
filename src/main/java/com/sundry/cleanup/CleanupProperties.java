package com.sundry.cleanup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 孤儿文件清理配置。宽限期是保护数据的关键，执行频率只影响空间回收速度。
 */
@Component
@ConfigurationProperties(prefix = "app.cleanup")
public class CleanupProperties {

    private boolean enabled = true;
    private String cron = "0 0 3 * * *";
    private Duration digitalGrace = Duration.ofDays(7);
    private Duration imageGrace = Duration.ofDays(1);
    private int batchSize = 100;
    private int maxBatchesPerRun = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Duration getDigitalGrace() {
        return digitalGrace;
    }

    public void setDigitalGrace(Duration digitalGrace) {
        this.digitalGrace = digitalGrace;
    }

    public Duration getImageGrace() {
        return imageGrace;
    }

    public void setImageGrace(Duration imageGrace) {
        this.imageGrace = imageGrace;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }
}
