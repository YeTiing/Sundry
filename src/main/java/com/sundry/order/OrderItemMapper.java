package com.sundry.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderItemMapper {

    int insertBatch(@Param("items") List<OrderItem> items);

    List<OrderItem> findByOrderId(@Param("orderId") Long orderId);

    OrderItem findByIdAndOrder(@Param("id") Long id, @Param("orderId") Long orderId);

    long countByDigitalFileId(@Param("fileId") Long fileId);
}
