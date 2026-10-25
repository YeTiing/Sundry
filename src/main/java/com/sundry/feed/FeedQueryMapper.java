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
}
