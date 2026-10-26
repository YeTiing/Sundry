package com.sundry.feed;

import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.catalog.dto.ProductView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedExploreServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private FeedQueryMapper queryMapper;

    @Test
    void swapsColdProductsIntoExplorePositions() {
        FeedProperties properties = new FeedProperties();
        properties.setExploreSlotsPercent(20);
        properties.setExploreExposureThreshold(5);

        Map<Long, ProductView> products = new HashMap<>();
        List<Long> ranked = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            ranked.add(id);
            products.put(id, product(id, 100, 10, ProductType.PHYSICAL));
        }
        products.put(98L, product(98, 100, 10, ProductType.PHYSICAL));
        products.put(97L, product(97, 100, 10, ProductType.PHYSICAL));
        products.put(99L, product(99, 200, 10, ProductType.PHYSICAL));
        stubViews(products);
        when(queryMapper.countProductExposures(anyList(), any())).thenReturn(List.of(
                exposure(98, 2), exposure(97, 10)));
        when(queryMapper.countCategoryExposures(any())).thenReturn(List.of(
                categoryExposure(100, 100), categoryExposure(200, 1)));

        FeedExploreService.ExploreResult result = new FeedExploreService(
                productService, queryMapper, properties)
                .apply(1L, ranked, List.of(98L, 97L, 99L));

        assertThat(result.changed()).isTrue();
        assertThat(result.ids()).hasSize(10);
        assertThat(result.ids()).doesNotHaveDuplicates();
        assertThat(result.ids().get(0)).isEqualTo(1L);
        assertThat(result.exploredIds()).containsExactlyInAnyOrder(99L, 98L);
        assertThat(result.ids().get(1)).isEqualTo(99L);
        assertThat(result.ids().get(4)).isEqualTo(98L);
    }

    @Test
    void leavesRankedListWhenNoColdCandidate() {
        FeedProperties properties = new FeedProperties();
        Map<Long, ProductView> products = new HashMap<>();
        List<Long> ranked = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            ranked.add(id);
            products.put(id, product(id, 100, 10, ProductType.PHYSICAL));
        }
        products.put(99L, product(99, 200, 10, ProductType.PHYSICAL));
        stubViews(products);
        when(queryMapper.countProductExposures(anyList(), any())).thenReturn(List.of(
                exposure(99, 100)));
        when(queryMapper.countCategoryExposures(any())).thenReturn(List.of());

        FeedExploreService.ExploreResult result = new FeedExploreService(
                productService, queryMapper, properties)
                .apply(1L, ranked, List.of(99L));

        assertThat(result.changed()).isFalse();
        assertThat(result.ids()).containsExactlyElementsOf(ranked);
    }

    @Test
    void skipsOutOfStockColdProduct() {
        FeedProperties properties = new FeedProperties();
        Map<Long, ProductView> products = new HashMap<>();
        List<Long> ranked = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            ranked.add(id);
            products.put(id, product(id, 100, 10, ProductType.PHYSICAL));
        }
        products.put(99L, product(99, 200, 0, ProductType.PHYSICAL));
        stubViews(products);
        when(queryMapper.countProductExposures(anyList(), any())).thenReturn(List.of());
        when(queryMapper.countCategoryExposures(any())).thenReturn(List.of());

        FeedExploreService.ExploreResult result = new FeedExploreService(
                productService, queryMapper, properties)
                .apply(1L, ranked, List.of(99L));

        assertThat(result.changed()).isFalse();
    }

    private void stubViews(Map<Long, ProductView> products) {
        when(productService.findPublicBatch(any(Collection.class))).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            Map<Long, ProductView> found = new HashMap<>();
            for (Long id : ids) {
                ProductView view = products.get(id);
                if (view != null) {
                    found.put(id, view);
                }
            }
            return found;
        });
    }

    private ProductView product(long id, long categoryId, int stock, ProductType type) {
        return new ProductView(id, "商品" + id, "测试", type, new BigDecimal("10.00"),
                stock, categoryId, "分类" + categoryId, null, null, null,
                ProductStatus.ON_SALE, null, LocalDateTime.now(), null, List.of());
    }

    private FeedQueryMapper.ProductExposureCount exposure(long id, long count) {
        FeedQueryMapper.ProductExposureCount row = new FeedQueryMapper.ProductExposureCount();
        row.setProductId(id);
        row.setExposures(count);
        return row;
    }

    private FeedQueryMapper.CategoryExposureCount categoryExposure(long id, long count) {
        FeedQueryMapper.CategoryExposureCount row = new FeedQueryMapper.CategoryExposureCount();
        row.setCategoryId(id);
        row.setExposures(count);
        return row;
    }
}
