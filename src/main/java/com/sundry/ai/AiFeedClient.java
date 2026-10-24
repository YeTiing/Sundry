package com.sundry.ai;

import com.sundry.ai.dto.RerankRequest;
import com.sundry.ai.dto.RerankResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 调用 Python 重排接口。Java 不解析模型原文，只接收经过 Python 校验的 ID 和理由。
 */
@Component
public class AiFeedClient {

    private final RestClient restClient;
    private final AiProperties properties;

    public AiFeedClient(RestClient aiRestClient, AiProperties properties) {
        this.restClient = aiRestClient;
        this.properties = properties;
    }

    public RerankResponse rerank(RerankRequest request, String traceId) {
        if (!properties.isEnabled()) {
            throw new AiFeedException("AI_DISABLED");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new AiFeedException("AI_TOKEN_MISSING");
        }
        try {
            RerankResponse response = restClient.post()
                    .uri("/internal/feed/rerank")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .header("X-Trace-Id", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
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
