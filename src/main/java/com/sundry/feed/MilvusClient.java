package com.sundry.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 2.5 REST API 客户端。不引入 Java SDK，避免多一个依赖。
 * 集合结构：id (Int64, primary) + vector (FloatVector)。
 */
@Component
public class MilvusClient {

    private static final Logger log = LoggerFactory.getLogger(MilvusClient.class);
    private static final int UPSERT_BATCH = 100;

    private final RestClient restClient;
    private final MilvusProperties properties;

    public MilvusClient(@Qualifier("milvusRestClient") RestClient restClient,
                        MilvusProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public void ensureCollection() {
        if (!properties.isEnabled()) {
            return;
        }
        JsonNode list = post("/v2/vectordb/collections/list", Map.of());
        for (JsonNode item : list.path("data")) {
            if (properties.getCollection().equals(item.asText())) {
                return;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", properties.getCollection());
        body.put("dimension", properties.getDim());
        body.put("metricType", properties.getMetric());
        body.put("primaryFieldName", "id");
        body.put("vectorFieldName", "vector");
        post("/v2/vectordb/collections/create", body);
        log.info("milvus collection created: {}", properties.getCollection());
    }

    public void upsert(List<Long> productIds, List<float[]> vectors) {
        if (!properties.isEnabled()) {
            throw new MilvusException("MILVUS_DISABLED");
        }
        if (productIds.size() != vectors.size()) {
            throw new MilvusException("MILVUS_INPUT_MISMATCH");
        }
        ensureCollection();
        for (int start = 0; start < productIds.size(); start += UPSERT_BATCH) {
            int end = Math.min(start + UPSERT_BATCH, productIds.size());
            List<Map<String, Object>> rows = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", productIds.get(i));
                row.put("vector", toList(vectors.get(i)));
                rows.add(row);
            }
            post("/v2/vectordb/entities/upsert", Map.of(
                    "collectionName", properties.getCollection(),
                    "data", rows));
        }
    }

    public List<Long> search(float[] vector, int limit) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        ensureCollection();
        JsonNode data = post("/v2/vectordb/entities/search", Map.of(
                "collectionName", properties.getCollection(),
                "data", List.of(toList(vector)),
                "annsField", "vector",
                "limit", limit,
                "outputFields", List.of("id"),
                "searchParams", Map.of(
                        "metricType", properties.getMetric(),
                        "params", Map.of()))).path("data");

        List<Long> ids = new ArrayList<>();
        if (data.isArray()) {
            JsonNode rows = data;
            if (data.size() > 0 && data.get(0).isArray()) {
                rows = data.get(0);
            }
            for (JsonNode row : rows) {
                JsonNode id = row.get("id");
                if (id != null) {
                    ids.add(id.asLong());
                }
            }
        }
        return ids;
    }

    private JsonNode post(String path, Object body) {
        try {
            JsonNode response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new MilvusException("MILVUS_EMPTY_RESPONSE");
            }
            int code = response.path("code").asInt(-1);
            if (code != 0) {
                throw new MilvusException("MILVUS_" + code + ": "
                        + response.path("message").asText("unknown"));
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new MilvusException("MILVUS_HTTP_" + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new MilvusException("MILVUS_UNAVAILABLE", ex);
        }
    }

    private List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
