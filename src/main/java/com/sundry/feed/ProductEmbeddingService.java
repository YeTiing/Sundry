package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品向量：全量重建、单商品增量、过期清理。
 * 增量由商品变更事件触发，定时扫描兜底。
 */
@Service
public class ProductEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingService.class);

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
        EmbedResult result = embedAndStore(products);
        return new RebuildResult(result.processed(), result.model(), result.dim());
    }

    /**
     * 单个商品增量：在售则重算，下架/删除则清掉向量。
     */
    public void refresh(Long productId) {
        if (!milvusProperties.isEnabled()) {
            return;
        }
        Product product = productMapper.findEmbeddingCandidate(productId);
        if (product == null) {
            delete(productId);
            return;
        }
        embedAndStore(List.of(product));
    }

    public void delete(Long productId) {
        if (milvusProperties.isEnabled()) {
            try {
                milvusClient.delete(List.of(productId));
            } catch (RuntimeException ex) {
                // 保留 MySQL 行，交给定时 orphan 扫描重试
                log.warn("Milvus 删除失败，稍后重试 productId={}", productId, ex);
                return;
            }
        }
        embeddingMapper.deleteByProductIds(List.of(productId));
    }

    /**
     * 补齐缺失/过期向量。每次扫描最多 maxBatches 批。
     */
    public int syncStale(int batchSize, int maxBatches) {
        if (!milvusProperties.isEnabled()) {
            return 0;
        }
        int size = Math.max(1, batchSize);
        int batches = Math.max(1, maxBatches);
        int processed = 0;
        for (int i = 0; i < batches; i++) {
            List<Product> stale = embeddingMapper.findStaleProducts(size);
            if (stale.isEmpty()) {
                break;
            }
            processed += embedAndStore(stale).processed();
            if (stale.size() < size) {
                break;
            }
        }
        return processed;
    }

    /**
     * 删除已下架/已删除商品的向量。Milvus 删除失败时保留 MySQL 行，下一轮重试。
     */
    public int removeOrphans(int batchSize) {
        List<Long> orphans = embeddingMapper.findOrphanProductIds(Math.max(1, batchSize));
        if (orphans.isEmpty()) {
            return 0;
        }
        if (milvusProperties.isEnabled()) {
            try {
                milvusClient.delete(orphans);
            } catch (RuntimeException ex) {
                log.warn("Milvus 批量删除失败，稍后重试 count={}", orphans.size(), ex);
                return 0;
            }
        }
        embeddingMapper.deleteByProductIds(orphans);
        return orphans.size();
    }

    public long count() {
        return embeddingMapper.count();
    }

    private EmbedResult embedAndStore(List<Product> products) {
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

        return new EmbedResult(processed, model, dim);
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

    private record EmbedResult(int processed, String model, int dim) {
    }

    public record RebuildResult(int products, String model, int dim) {
    }
}
