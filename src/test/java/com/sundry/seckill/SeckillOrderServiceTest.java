package com.sundry.seckill;

import com.sundry.address.UserAddressMapper;
import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.order.OrderItemMapper;
import com.sundry.order.OrderMapper;
import com.sundry.order.OrderNoGenerator;
import com.sundry.order.OrderProperties;
import com.sundry.seckill.dto.SeckillResultView;
import com.sundry.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillOrderServiceTest {

    @Test
    void redisFailureAfterDbCommitDoesNotRollbackStock() {
        SeckillActivityMapper activityMapper = mock(SeckillActivityMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        SeckillStockService stockService = mock(SeckillStockService.class);
        SeckillResultService resultService = mock(SeckillResultService.class);
        SeckillTimeoutService timeoutService = mock(SeckillTimeoutService.class);
        OrderProperties orderProperties = mock(OrderProperties.class);
        SeckillMetrics metrics = mock(SeckillMetrics.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        SeckillOrderService service = new SeckillOrderService(
                activityMapper, orderMapper, mock(OrderItemMapper.class), productMapper,
                mock(UserAddressMapper.class), mock(ObjectStorage.class), stockService,
                resultService, timeoutService, mock(SeckillOrderPublisher.class),
                mock(OrderNoGenerator.class), orderProperties, mock(SeckillRateLimiter.class),
                metrics, transactionTemplate);

        SeckillOrderMessage message = new SeckillOrderMessage("req-1", 2L, 1L, null);
        when(resultService.get("req-1")).thenReturn(
                new SeckillResultView("req-1", "PROCESSING", null, null, null));
        when(orderMapper.findSeckillOrder(1L, 2L)).thenReturn(null);

        SeckillActivity activity = mock(SeckillActivity.class);
        when(activity.getId()).thenReturn(2L);
        when(activity.getProductId()).thenReturn(3L);
        when(activity.getStatus()).thenReturn(SeckillStatus.ACTIVE);
        when(activity.getStartAt()).thenReturn(LocalDateTime.now().minusMinutes(1));
        when(activity.getEndAt()).thenReturn(LocalDateTime.now().plusHours(1));
        when(activityMapper.findById(2L)).thenReturn(activity);

        Product product = mock(Product.class);
        when(product.getType()).thenReturn(ProductType.DIGITAL);
        when(product.getStatus()).thenReturn(ProductStatus.ON_SALE);
        when(product.getDeletedAt()).thenReturn(null);
        when(productMapper.findById(3L)).thenReturn(product);

        when(orderProperties.getPayTimeout()).thenReturn(Duration.ofMinutes(30));
        when(transactionTemplate.execute(any())).thenReturn("ORDER-1");
        doThrow(new RuntimeException("redis down")).when(timeoutService)
                .add(eq("ORDER-1"), any(LocalDateTime.class));

        service.process(message);

        // DB 已提交，Redis 超时队列失败不能回补库存或标记失败
        verify(stockService, never()).rollbackReservation(anyLong(), anyLong(), anyInt());
        verify(resultService, never()).fail(anyString(), anyString(), anyString());
        verify(resultService).success("req-1", "ORDER-1");
        verify(metrics).orderSuccess();
    }
}
