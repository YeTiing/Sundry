package com.sundry.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
}
