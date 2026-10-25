package com.sundry.nl2sql;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.nl2sql")
public class Nl2sqlProperties {

    private boolean enabled = false;
    private boolean freeSqlEnabled = false;
    private int queryTimeoutSeconds = 3;
    private int maxRows = 1000;
    private int rateLimitPerMinute = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFreeSqlEnabled() {
        return freeSqlEnabled;
    }

    public void setFreeSqlEnabled(boolean freeSqlEnabled) {
        this.freeSqlEnabled = freeSqlEnabled;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
}
