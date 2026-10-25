package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品向量重建：MySQL 取商品 -> Python embedding -> MySQL + Milvus。
 * 由管理员手动触发，不是请求链路的一部分。
 */
@Service
public class ProductEmbeddingService {

    // 百炼 embedding 单批最多 25 条
    private static final int EMBED_BATCH = 25;

    private final ProductMapper productMapper;
    private final ProductEmbeddingMapper embeddingMapper;
    private final EmbeddingClient embeddingClient;
    private final MilvusClient milvusClient;
    private final MilvusProperties milvusProperties;
    private final ObjectMapper objectMapper;

    public ProductEmbeddingService(ProductMapper productMapper,
                                   ProductEmbeddingMapper embeddingMapper,
                                   EmbeddingClient embeddingClient,
                                   MilvusClient milvusClient,
                                   MilvusProperties milvusProperties,
                                   ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingClient = embeddingClient;
        this.milvusClient = milvusClient;
        this.milvusProperties = milvusProperties;
        this.objectMapper = objectMapper;
    }

    public RebuildResult rebuild() {
        if (!milvusProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE);
        }
        List<Product> products = productMapper.findAllForEmbedding();
        if (products.isEmpty()) {
            return new RebuildResult(0, null, milvusProperties.getDim());
        }

        String model = null;
        int dim = milvusProperties.getDim();
        int processed = 0;

        for (int start = 0; start < products.size(); start += EMBED_BATCH) {
            List<Product> batch = products.subList(start,
                    Math.min(start + EMBED_BATCH, products.size()));
            List<String> texts = batch.stream().map(this::textOf).toList();

            EmbeddingClient.EmbeddingResult result;
            try {
                result = embeddingClient.embed(texts);
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.EMBEDDING_UNAVAILABLE);
            }
            model = result.model();
            dim = result.dim();
            if (dim != milvusProperties.getDim()) {
                throw new BusinessException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
            }

            List<ProductEmbedding> rows = new ArrayList<>(batch.size());
            List<Long> ids = new ArrayList<>(batch.size());
            List<float[]> vectors = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                float[] vector = toArray(result.vectors().get(i));
                ProductEmbedding row = new ProductEmbedding();
                row.setProductId(batch.get(i).getId());
                row.setModel(result.model());
                row.setDim(result.dim());
                row.setVector(objectMapper.writeValueAsString(vector));
                rows.add(row);
                ids.add(batch.get(i).getId());
                vectors.add(vector);
            }

            embeddingMapper.upsertBatch(rows);
            try {
                milvusClient.upsert(ids, vectors);
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE);
            }
            processed += batch.size();
        }

        return new RebuildResult(processed, model, dim);
    }

    public long count() {
        return embeddingMapper.count();
    }

    private String textOf(Product product) {
        String text = product.getName() + " "
                + (product.getDescription() == null ? "" : product.getDescription())
                + " 分类:" + (product.getCategoryName() == null ? "" : product.getCategoryName());
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private float[] toArray(List<Float> values) {
        float[] array = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    public record RebuildResult(int products, String model, int dim) {
    }
}
