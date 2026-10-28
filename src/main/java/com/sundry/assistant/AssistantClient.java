package com.sundry.assistant;

import com.sundry.ai.AiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Python 助手意图接口。只拿工具选择结果，不执行任何操作。
 */
@Component
public class AssistantClient {

    private final RestClient restClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final AssistantLlmGate gate;

    public AssistantClient(@Qualifier("assistantRestClient") RestClient restClient,
                           AiProperties properties, ObjectMapper objectMapper,
                           AssistantLlmGate gate) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.gate = gate;
    }

    public AssistantLlmResponse chat(AssistantLlmRequest request, String traceId) {
        if (!properties.isEnabled()) {
            throw new AssistantException("AI_DISABLED");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new AssistantException("AI_TOKEN_MISSING");
        }
        gate.ensureReady();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (RuntimeException ex) {
            throw new AssistantException("AI_REQUEST_INVALID", ex);
        }

        try {
            AssistantLlmResponse response = restClient.post()
                    .uri("/internal/assistant/chat")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .header("X-Trace-Id", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(AssistantLlmResponse.class);
            if (response == null) {
                throw new AssistantException("AI_EMPTY_RESPONSE");
            }
            return response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 429) {
                gate.coolDown();
            }
            throw new AssistantException("AI_HTTP_" + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new AssistantException("AI_UNAVAILABLE", ex);
        }
    }
}
