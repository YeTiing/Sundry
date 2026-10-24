package com.sundry.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderRefundMapper {

    int insert(OrderRefund refund);

    OrderRefund findLatestByOrderId(@Param("orderId") Long orderId);

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
