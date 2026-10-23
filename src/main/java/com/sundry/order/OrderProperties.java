package com.sundry.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 订单超时与清理配置。
 */
@Component
@ConfigurationProperties(prefix = "app.order")
public class OrderProperties {

    private Duration payTimeout = Duration.ofMinutes(30);
    private int cancelBatchSize = 100;

    public Duration getPayTimeout() {
        return payTimeout;
    }

    public void setPayTimeout(Duration payTimeout) {
        this.payTimeout = payTimeout;
    }

    public int getCancelBatchSize() {
        return cancelBatchSize;
    }

    public void setCancelBatchSize(int cancelBatchSize) {
        this.cancelBatchSize = cancelBatchSize;
    }
}
