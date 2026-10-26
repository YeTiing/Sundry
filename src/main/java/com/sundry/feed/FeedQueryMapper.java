package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedQueryMapper {

    List<Long> findPurchasedProductIds(@Param("userId") Long userId);

    List<CategoryWeight> findPurchasedCategoryWeights(@Param("userId") Long userId);

    List<CategoryWeight> findViewCategoryWeights(@Param("userId") Long userId,
                                                 @Param("since") LocalDateTime since);

    PriceRange findPriceRange(@Param("userId") Long userId,
                              @Param("since") LocalDateTime since);

    List<String> findRecentViewedNames(@Param("userId") Long userId,
                                       @Param("since") LocalDateTime since,
                                       @Param("limit") int limit);

    List<Long> findHotProductIds(@Param("since") LocalDateTime since,
                                 @Param("limit") int limit);

    List<Long> findNewProductIds(@Param("limit") int limit);

    List<Long> findProductIdsByCategories(@Param("categoryIds") List<Long> categoryIds,
                                          @Param("limit") int limit);

    List<Long> findCoPurchaseProductIds(@Param("userId") Long userId,
                                        @Param("limit") int limit);

    List<Long> findRecentViewedProductIds(@Param("userId") Long userId,
                                          @Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    List<Long> findRecentPurchasedProductIds(@Param("userId") Long userId,
                                             @Param("limit") int limit);

    List<ProductExposureCount> countProductExposures(@Param("productIds") List<Long> productIds,
                                                     @Param("since") LocalDateTime since);

    List<CategoryExposureCount> countCategoryExposures(@Param("since") LocalDateTime since);

    class ProductExposureCount {
        private Long productId;
        private Long exposures;

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }
    }

    class CategoryExposureCount {
        private Long categoryId;
        private Long exposures;

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
        }

        public Long getExposures() {
            return exposures;
        }

        public void setExposures(Long exposures) {
            this.exposures = exposures;
        }
    }
}
