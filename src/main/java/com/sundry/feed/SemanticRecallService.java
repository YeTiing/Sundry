package com.sundry.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义召回：用户最近浏览/购买的向量加权平均，作为查询向量去 Milvus ANN。
 * Milvus 不可用或没有向量时返回空，其他召回路线不受影响。
 */
@Service
public class SemanticRecallService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecallService.class);

    private static final int VIEW_DAYS = 14;
    private static final int RECENT_VIEW_LIMIT = 30;
    private static final int RECENT_PURCHASE_LIMIT = 10;
    private static final double VIEW_WEIGHT = 1.0;
    private static final double PURCHASE_WEIGHT = 3.0;

    private final FeedQueryMapper feedQueryMapper;
    private final ProductEmbeddingMapper embeddingMapper;
    private final MilvusClient milvusClient;
    private final MilvusProperties milvusProperties;
    private final ObjectMapper objectMapper;

    public SemanticRecallService(FeedQueryMapper feedQueryMapper,
                                 ProductEmbeddingMapper embeddingMapper,
                                 MilvusClient milvusClient,
                                 MilvusProperties milvusProperties,
                                 ObjectMapper objectMapper) {
        this.feedQueryMapper = feedQueryMapper;
        this.embeddingMapper = embeddingMapper;
        this.milvusClient = milvusClient;
        this.milvusProperties = milvusProperties;
        this.objectMapper = objectMapper;
    }

    public List<Long> recall(Long userId, int limit) {
        if (userId == null || !milvusProperties.isEnabled()) {
            return List.of();
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(VIEW_DAYS);
            List<Long> viewed = feedQueryMapper.findRecentViewedProductIds(
                    userId, since, RECENT_VIEW_LIMIT);
            List<Long> purchased = feedQueryMapper.findRecentPurchasedProductIds(
                    userId, RECENT_PURCHASE_LIMIT);

            Map<Long, Double> weights = new LinkedHashMap<>();
            for (Long productId : viewed) {
                weights.merge(productId, VIEW_WEIGHT, Double::sum);
            }
            for (Long productId : purchased) {
                weights.merge(productId, PURCHASE_WEIGHT, Double::sum);
            }
            if (weights.isEmpty()) {
                return List.of();
            }

            List<ProductEmbedding> embeddings = embeddingMapper.findByProductIds(
                    new ArrayList<>(weights.keySet()));
            int dim = milvusProperties.getDim();
            double[] sum = new double[dim];
            double totalWeight = 0;

            for (ProductEmbedding embedding : embeddings) {
                double weight = weights.getOrDefault(embedding.getProductId(), 0.0);
                if (weight <= 0) {
                    continue;
                }
                float[] vector = objectMapper.readValue(embedding.getVector(), float[].class);
                if (vector.length != dim) {
                    continue;
                }
                for (int i = 0; i < dim; i++) {
                    sum[i] += vector[i] * weight;
                }
                totalWeight += weight;
            }
            if (totalWeight <= 0) {
                return List.of();
            }

            float[] query = new float[dim];
            double norm = 0;
            for (int i = 0; i < dim; i++) {
                query[i] = (float) (sum[i] / totalWeight);
                norm += query[i] * query[i];
            }
            norm = Math.sqrt(norm);
            if (norm <= 0) {
                return List.of();
            }
            for (int i = 0; i < dim; i++) {
                query[i] /= (float) norm;
            }

            return milvusClient.search(query, Math.max(1, limit));
        } catch (RuntimeException ex) {
            log.warn("semantic recall failed userId={}", userId, ex);
            return List.of();
        }
    }
}
