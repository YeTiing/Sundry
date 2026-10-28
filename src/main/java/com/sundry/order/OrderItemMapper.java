package com.sundry.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderItemMapper {

    int insertBatch(@Param("items") List<OrderItem> items);

    List<OrderItem> findByOrderId(@Param("orderId") Long orderId);

    // 列表页批量加载，避免逐单查询
    List<OrderItem> findByOrderIds(@Param("orderIds") List<Long> orderIds);

    OrderItem findByIdAndOrder(@Param("id") Long id, @Param("orderId") Long orderId);

    long countByDigitalFileId(@Param("fileId") Long fileId);
}
