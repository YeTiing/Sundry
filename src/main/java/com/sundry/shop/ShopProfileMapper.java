package com.sundry.shop;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ShopProfileMapper {

    ShopProfile find();

    int update(@Param("shopName") String shopName,
               @Param("description") String description,
               @Param("contact") String contact,
               @Param("shippingAddress") String shippingAddress,
               @Param("logoFileId") Long logoFileId);
}
