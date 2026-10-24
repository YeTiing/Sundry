package com.sundry.ai;

import com.sundry.ai.dto.RerankRequest;
import com.sundry.ai.dto.RerankResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * 调用 Python 重排接口。Java 不解析模型原文，只接收经过 Python 校验的 ID 和理由。
 *
 * 请求体先序列化成 String 再发送：RestClient 直接把对象交给转换器时会用
 * Transfer-Encoding: chunked，本地 uvicorn 对这条路径处理不稳定，固定长度更可靠。
 */
@Component
public class AiFeedClient {

    private final RestClient restClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public AiFeedClient(RestClient aiRestClient, AiProperties properties, ObjectMapper objectMapper) {
        this.restClient = aiRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RerankResponse rerank(RerankRequest request, String traceId) {
        if (!properties.isEnabled()) {
            throw new AiFeedException("AI_DISABLED");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new AiFeedException("AI_TOKEN_MISSING");
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (RuntimeException ex) {
            throw new AiFeedException("AI_REQUEST_INVALID", ex);
        }

        try {
            RerankResponse response = restClient.post()
                    .uri("/internal/feed/rerank")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .header("X-Trace-Id", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(RerankResponse.class);
            if (response == null) {
                throw new AiFeedException("AI_EMPTY_RESPONSE");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new AiFeedException("AI_HTTP_" + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new AiFeedException("AI_UNAVAILABLE", ex);
        }
    }
}
