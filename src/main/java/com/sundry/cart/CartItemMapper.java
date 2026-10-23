package com.sundry.cart;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CartItemMapper {

    List<CartItem> findByUser(@Param("userId") Long userId);

    CartItem findByUserAndProduct(@Param("userId") Long userId,
                                  @Param("productId") Long productId);

    CartItem findByIdAndUser(@Param("id") Long id,
                             @Param("userId") Long userId);

    int insert(CartItem item);

    int updateQuantity(@Param("id") Long id,
                       @Param("userId") Long userId,
                       @Param("quantity") int quantity);

    int deleteByIdAndUser(@Param("id") Long id,
                          @Param("userId") Long userId);
}
