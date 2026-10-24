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
}
