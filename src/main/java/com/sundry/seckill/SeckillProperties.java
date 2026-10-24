package com.sundry.seckill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.seckill")
public class SeckillProperties {

    private String topic = "sundry.seckill.order.create";
    private Duration resultTtl = Duration.ofMinutes(30);
    private int timeoutBatchSize = 100;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Duration getResultTtl() {
        return resultTtl;
    }

    public void setResultTtl(Duration resultTtl) {
        this.resultTtl = resultTtl;
    }

    public int getTimeoutBatchSize() {
        return timeoutBatchSize;
    }

    public void setTimeoutBatchSize(int timeoutBatchSize) {
        this.timeoutBatchSize = timeoutBatchSize;
    }
}
