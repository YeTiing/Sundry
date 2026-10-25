package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductEmbeddingServiceTest {

    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final ProductEmbeddingMapper embeddingMapper = mock(ProductEmbeddingMapper.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final MilvusClient milvusClient = mock(MilvusClient.class);
    private final MilvusProperties properties = new MilvusProperties();

    private ProductEmbeddingService service() {
        return new ProductEmbeddingService(
                productMapper, embeddingMapper, embeddingClient, milvusClient,
                properties, new ObjectMapper());
    }

    @Test
    void rebuildsProductsAndUpsertsBothStores() {
        properties.setEnabled(true);
        properties.setDim(2);
        when(productMapper.findAllForEmbedding())
                .thenReturn(List.of(product(1L, "商品1"), product(2L, "商品2")));
        when(embeddingClient.embed(anyList())).thenReturn(
                new EmbeddingClient.EmbeddingResult(
                        List.of(List.of(1.0f, 0.0f), List.of(0.0f, 1.0f)),
                        "test-model", 2));

        ProductEmbeddingService.RebuildResult result = service().rebuild();

        assertThat(result.products()).isEqualTo(2);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.dim()).isEqualTo(2);
        verify(embeddingMapper).upsertBatch(anyList());
        verify(milvusClient).upsert(anyList(), anyList());
    }

    @Test
    void refreshUpsertsSingleProduct() {
        properties.setEnabled(true);
        properties.setDim(2);
        when(productMapper.findEmbeddingCandidate(1L)).thenReturn(product(1L, "商品1"));
        when(embeddingClient.embed(anyList())).thenReturn(
                new EmbeddingClient.EmbeddingResult(
                        List.of(List.of(1.0f, 0.0f)), "test-model", 2));

        service().refresh(1L);

        verify(embeddingMapper).upsertBatch(anyList());
        verify(milvusClient).upsert(anyList(), anyList());
    }

    @Test
    void refreshMissingProductDeletesVector() {
        properties.setEnabled(true);
        when(productMapper.findEmbeddingCandidate(1L)).thenReturn(null);

        service().refresh(1L);

        verify(milvusClient).delete(List.of(1L));
        verify(embeddingMapper).deleteByProductIds(List.of(1L));
    }

    @Test
    void syncStaleProcessesMissingProducts() {
        properties.setEnabled(true);
        properties.setDim(2);
        when(embeddingMapper.findStaleProducts(200))
                .thenReturn(List.of(product(1L, "商品1")));
        when(embeddingClient.embed(anyList())).thenReturn(
                new EmbeddingClient.EmbeddingResult(
                        List.of(List.of(1.0f, 0.0f)), "test-model", 2));

        int processed = service().syncStale(200, 4);

        assertThat(processed).isEqualTo(1);
        verify(embeddingMapper).upsertBatch(anyList());
        verify(milvusClient).upsert(anyList(), anyList());
    }

    @Test
    void removeOrphansDeletesBothStores() {
        properties.setEnabled(true);
        when(embeddingMapper.findOrphanProductIds(200)).thenReturn(List.of(1L, 2L));

        int removed = service().removeOrphans(200);

        assertThat(removed).isEqualTo(2);
        verify(milvusClient).delete(List.of(1L, 2L));
        verify(embeddingMapper).deleteByProductIds(List.of(1L, 2L));
    }

    @Test
    void failsWhenMilvusDisabled() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service().rebuild())
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(productMapper, embeddingMapper, embeddingClient, milvusClient);
    }

    private Product product(long id, String name) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setDescription("描述");
        product.setCategoryName("分类");
        return product;
    }
}
