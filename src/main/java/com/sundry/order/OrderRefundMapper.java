package com.sundry.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderRefundMapper {

    int insert(OrderRefund refund);

    OrderRefund findLatestByOrderId(@Param("orderId") Long orderId);

    // 列表页批量加载每个订单的最新退款单
    List<OrderRefund> findLatestByOrderIds(@Param("orderIds") List<Long> orderIds);

    int approve(@Param("id") Long id, @Param("reviewerId") Long reviewerId,
                @Param("remark") String remark);

    int approveReturn(@Param("id") Long id, @Param("reviewerId") Long reviewerId,
                      @Param("remark") String remark);

    int markReturnShipped(@Param("id") Long id, @Param("userId") Long userId,
                          @Param("trackingNo") String trackingNo);

    int completeReturn(@Param("id") Long id, @Param("reviewerId") Long reviewerId);

    int reject(@Param("id") Long id, @Param("reviewerId") Long reviewerId,
               @Param("remark") String remark);

    int withdraw(@Param("id") Long id, @Param("userId") Long userId);
}
