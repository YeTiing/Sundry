package com.sundry.seckill;

import com.sundry.order.Order;
import com.sundry.order.OrderMapper;
import com.sundry.seckill.dto.SeckillResultView;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillResultServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void processingFallsBackToDbWhenResultCacheWasNotUpdated() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.entries("seckill:result:req-1")).thenReturn(Map.of(
                "status", "PROCESSING",
                "userId", "1",
                "activityId", "2"));

        Order order = new Order();
        order.setOrderNo("ORDER-1");
        when(orderMapper.findSeckillOrder(1L, 2L)).thenReturn(order);

        SeckillResultService service = new SeckillResultService(
                redis, mock(SeckillProperties.class), orderMapper);

        SeckillResultView view = service.get("req-1");

        assertThat(view.status()).isEqualTo("SUCCESS");
        assertThat(view.orderNo()).isEqualTo("ORDER-1");
    }
}
