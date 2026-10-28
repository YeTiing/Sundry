package com.sundry.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * AI 服务的 HTTP 客户端。
 *
 * 多个 RestClient 共用同一个 JDK HttpClient：连接池和 keep-alive 复用，
 * 读超时按调用方单独设置（助手 3s、NL2SQL 8s、Feed 30s）。
 */
@Configuration
public class AiConfig {

    @Bean
    public HttpClient aiHttpClient(@Value("${app.ai.connect-timeout:2s}") Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Python 侧是 uvicorn，走 HTTP/1.1 即可，避免 HTTP/2 升级开销
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    public RestClient aiRestClient(AiProperties properties, HttpClient aiHttpClient) {
        return client(properties, aiHttpClient, properties.getTimeout());
    }

    /**
     * 助手单独 3 秒读超时：模型选工具不该拖住用户。
     */
    @Bean
    public RestClient assistantRestClient(AiProperties properties, HttpClient aiHttpClient,
                                          @Value("${app.assistant.timeout:3s}") Duration timeout) {
        return client(properties, aiHttpClient, timeout);
    }

    @Bean
    public RestClient nl2sqlRestClient(AiProperties properties, HttpClient aiHttpClient,
                                       @Value("${app.nl2sql.plan-timeout:8s}") Duration timeout) {
        return client(properties, aiHttpClient, timeout);
    }

    private RestClient client(AiProperties properties, HttpClient httpClient,
                              Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
