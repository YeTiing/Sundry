package com.sundry.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI 服务的 HTTP 客户端。超时同时作用于连接和读取，避免拖住后台 worker。
 */
@Configuration
public class AiConfig {

    @Bean
    public RestClient aiRestClient(AiProperties properties) {
        int timeout = (int) Math.min(Integer.MAX_VALUE, properties.getTimeout().toMillis());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 助手单独用 8 秒超时：模型选工具不该拖住用户。
     */
    @Bean
    public RestClient assistantRestClient(AiProperties properties,
                                          @Value("${app.assistant.timeout:8s}") Duration timeout) {
        return shortClient(properties, timeout);
    }

    @Bean
    public RestClient nl2sqlRestClient(AiProperties properties,
                                       @Value("${app.nl2sql.plan-timeout:8s}") Duration timeout) {
        return shortClient(properties, timeout);
    }

    private RestClient shortClient(AiProperties properties, Duration timeout) {
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
