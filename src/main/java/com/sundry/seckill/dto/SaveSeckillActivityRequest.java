package com.sundry.seckill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新建 / 编辑秒杀活动。只有草稿可以编辑。
 */
public record SaveSeckillActivityRequest(
        @NotNull(message = "商品不能为空")
        Long productId,

        @NotNull(message = "秒杀价不能为空")
        @DecimalMin(value = "0.01", message = "秒杀价必须大于 0")
        @Digits(integer = 8, fraction = 2, message = "秒杀价最多 8 位整数和 2 位小数")
        BigDecimal seckillPrice,

        @NotNull(message = "活动库存不能为空")
        @Min(value = 1, message = "活动库存至少为 1")
        Integer stock,

        @Min(value = 1, message = "每人限购至少为 1")
        @Max(value = 1, message = "每人限购固定为 1")
        Integer perUserLimit,

        @NotNull(message = "开始时间不能为空")
        LocalDateTime startAt,

        @NotNull(message = "结束时间不能为空")
        LocalDateTime endAt) {
}
