package com.sundry.seckill;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillActivityMapper {

    int insert(SeckillActivity activity);

    int updateDraft(SeckillActivity activity);

    SeckillActivity findById(@Param("id") Long id);

    SeckillActivity findActiveByProduct(@Param("productId") Long productId);

    List<SeckillActivity> findPageAdmin(@Param("status") SeckillStatus status,
                                        @Param("offset") long offset,
                                        @Param("size") int size);

    long countAdmin(@Param("status") SeckillStatus status);

    long countOverlapping(@Param("productId") Long productId,
                          @Param("startAt") LocalDateTime startAt,
                          @Param("endAt") LocalDateTime endAt,
                          @Param("excludeId") Long excludeId,
                          @Param("statuses") List<SeckillStatus> statuses);

    int activate(@Param("id") Long id);

    int cancel(@Param("id") Long id);

    int end(@Param("id") Long id);

    int incrementSold(@Param("id") Long id);

    int decrementSold(@Param("id") Long id);

    List<SeckillActivity> findActiveForWarmUp();

    List<SeckillActivity> findFeatured(@Param("limit") int limit);
}
