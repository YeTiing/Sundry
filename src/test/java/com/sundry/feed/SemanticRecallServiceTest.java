package com.sundry.feed;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticRecallServiceTest {

    private final FeedQueryMapper feedQueryMapper = mock(FeedQueryMapper.class);
    private final ProductEmbeddingMapper embeddingMapper = mock(ProductEmbeddingMapper.class);
    private final MilvusClient milvusClient = mock(MilvusClient.class);
    private final MilvusProperties properties = new MilvusProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SemanticRecallService service() {
        return new SemanticRecallService(
                feedQueryMapper, embeddingMapper, milvusClient, properties, objectMapper);
    }

    @Test
    void buildsWeightedNormalizedVectorAndSearches() {
        properties.setEnabled(true);
        properties.setDim(2);
        when(feedQueryMapper.findRecentViewedProductIds(eq(1L), any(LocalDateTime.class), eq(30)))
                .thenReturn(List.of(10L));
        when(feedQueryMapper.findRecentPurchasedProductIds(1L, 10))
                .thenReturn(List.of(20L));
        when(embeddingMapper.findByProductIds(anyList()))
                .thenReturn(List.of(embedding(10L, "[1.0,0.0]"), embedding(20L, "[0.0,1.0]")));
        when(milvusClient.search(any(float[].class), eq(5))).thenReturn(List.of(30L, 40L));

        List<Long> ids = service().recall(1L, 5);

        assertThat(ids).containsExactly(30L, 40L);
        ArgumentCaptor<float[]> captor = ArgumentCaptor.forClass(float[].class);
        verify(milvusClient).search(captor.capture(), eq(5));
        // 浏览权重 1、购买权重 3；平均后 (0.25, 0.75)，归一化 (0.3162, 0.9487)
        assertThat(captor.getValue()[0]).isCloseTo(0.3162f, within(0.001f));
        assertThat(captor.getValue()[1]).isCloseTo(0.9487f, within(0.001f));
    }

    @Test
    void disabledMilvusReturnsEmpty() {
        properties.setEnabled(false);
        assertThat(service().recall(1L, 5)).isEmpty();
        verifyNoInteractions(feedQueryMapper, embeddingMapper, milvusClient);
    }

    @Test
    void noHistoryReturnsEmpty() {
        properties.setEnabled(true);
        properties.setDim(2);
        when(feedQueryMapper.findRecentViewedProductIds(eq(1L), any(LocalDateTime.class), eq(30)))
                .thenReturn(List.of());
        when(feedQueryMapper.findRecentPurchasedProductIds(1L, 10)).thenReturn(List.of());

        assertThat(service().recall(1L, 5)).isEmpty();
        verify(milvusClient, never()).search(any(float[].class), anyInt());
    }

    @Test
    void milvusFailureFallsBackToEmpty() {
        properties.setEnabled(true);
        properties.setDim(2);
        when(feedQueryMapper.findRecentViewedProductIds(eq(1L), any(LocalDateTime.class), eq(30)))
                .thenReturn(List.of(10L));
        when(feedQueryMapper.findRecentPurchasedProductIds(1L, 10)).thenReturn(List.of());
        when(embeddingMapper.findByProductIds(anyList()))
                .thenReturn(List.of(embedding(10L, "[1.0,0.0]")));
        when(milvusClient.search(any(float[].class), eq(5)))
                .thenThrow(new MilvusException("down"));

        assertThat(service().recall(1L, 5)).isEmpty();
    }

    private ProductEmbedding embedding(long productId, String vector) {
        ProductEmbedding embedding = new ProductEmbedding();
        embedding.setProductId(productId);
        embedding.setModel("test");
        embedding.setDim(2);
        embedding.setVector(vector);
        return embedding;
    }
}
