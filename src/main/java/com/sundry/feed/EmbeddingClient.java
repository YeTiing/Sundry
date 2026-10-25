package com.sundry.feed;

import com.sundry.ai.AiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Java -> Python /internal/embeddings。Python 持有百炼 key，Java 不直接调厂商。
 */
@Component
public class EmbeddingClient {

    private final RestClient restClient;
    private final AiProperties properties;

    public EmbeddingClient(@Qualifier("aiRestClient") RestClient restClient,
                           AiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public EmbeddingResult embed(List<String> texts) {
        if (!properties.isEnabled()) {
            throw new MilvusException("EMBEDDING_DISABLED");
        }
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/internal/embeddings")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbeddingRequest(texts))
                    .retrieve()
                    .body(EmbeddingResponse.class);
            if (response == null || response.vectors() == null
                    || response.vectors().size() != texts.size()) {
                throw new MilvusException("EMBEDDING_EMPTY_RESPONSE");
            }
            return new EmbeddingResult(response.vectors(), response.model(), response.dim());
        } catch (RestClientResponseException ex) {
            throw new MilvusException("EMBEDDING_HTTP_" + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new MilvusException("EMBEDDING_UNAVAILABLE", ex);
        }
    }

    public record EmbeddingRequest(List<String> texts) {
    }

    public record EmbeddingResponse(List<List<Float>> vectors, String model, int dim) {
    }

    public record EmbeddingResult(List<List<Float>> vectors, String model, int dim) {
    }
}
