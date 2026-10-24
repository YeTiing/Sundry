package com.sundry.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {

    int insert(Order order);

    Order findByUserAndRequestId(@Param("userId") Long userId,
                                 @Param("requestId") String requestId);

    Order findByOrderNoAndUser(@Param("orderNo") String orderNo,
                               @Param("userId") Long userId);

    Order findByOrderNo(@Param("orderNo") String orderNo);

    List<Order> findPageByUser(@Param("userId") Long userId,
                               @Param("offset") long offset,
                               @Param("size") int size);

    long countByUser(@Param("userId") Long userId);

    List<Order> findPageAdmin(@Param("status") OrderStatus status,
                              @Param("offset") long offset,
                              @Param("size") int size);

    long countAdmin(@Param("status") OrderStatus status);

    List<Order> findTimeoutCandidates(@Param("cutoff") LocalDateTime cutoff,
                                      @Param("limit") int limit);

    int markPaid(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    int cancel(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    int confirm(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    int pack(@Param("orderNo") String orderNo);

    int ship(@Param("orderNo") String orderNo, @Param("trackingNo") String trackingNo);

    int cancelById(@Param("id") Long id);

    int cancelByOrderNo(@Param("orderNo") String orderNo);

    int requestRefund(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    int approveRefund(@Param("orderNo") String orderNo);

    int revertRefund(@Param("orderNo") String orderNo, @Param("status") OrderStatus status);

    int requestReturn(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    int approveReturn(@Param("orderNo") String orderNo);

    int markReturnShipped(@Param("orderNo") String orderNo);

    int completeReturn(@Param("orderNo") String orderNo);

    Order findSeckillOrder(@Param("userId") Long userId,
                           @Param("activityId") Long activityId);

    List<Long> findSeckillBuyerIds(@Param("activityId") Long activityId);

    long countActiveSeckillOrders(@Param("activityId") Long activityId);
}
