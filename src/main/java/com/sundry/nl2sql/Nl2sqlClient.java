package com.sundry.nl2sql;

import com.sundry.ai.AiProperties;
import com.sundry.nl2sql.dto.Nl2sqlPlanRequest;
import com.sundry.nl2sql.dto.Nl2sqlPlanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * 只向 Python 要查询计划；Java 永远不把 SQL 生成权交给模型。
 */
@Component
public class Nl2sqlClient {

    private final RestClient restClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public Nl2sqlClient(@Qualifier("nl2sqlRestClient") RestClient restClient,
                        AiProperties aiProperties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public Nl2sqlPlanResponse plan(Nl2sqlPlanRequest request, String traceId) {
        if (!aiProperties.isEnabled()) {
            throw new Nl2sqlException("AI_DISABLED");
        }
        if (aiProperties.getInternalToken() == null || aiProperties.getInternalToken().isBlank()) {
            throw new Nl2sqlException("AI_TOKEN_MISSING");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (RuntimeException ex) {
            throw new Nl2sqlException("REQUEST_INVALID", ex);
        }
        try {
            Nl2sqlPlanResponse response = restClient.post()
                    .uri("/internal/nl2sql/plan")
                    .header("X-Internal-Token", aiProperties.getInternalToken())
                    .header("X-Trace-Id", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Nl2sqlPlanResponse.class);
            if (response == null) {
                throw new Nl2sqlException("EMPTY_RESPONSE");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new Nl2sqlException("AI_HTTP_" + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new Nl2sqlException("AI_UNAVAILABLE", ex);
        }
    }
}
