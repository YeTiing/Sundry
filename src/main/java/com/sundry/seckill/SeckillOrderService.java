package com.sundry.seckill;

import com.sundry.address.UserAddress;
import com.sundry.address.UserAddressMapper;
import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.order.Order;
import com.sundry.order.OrderItem;
import com.sundry.order.OrderItemMapper;
import com.sundry.order.OrderMapper;
import com.sundry.order.OrderNoGenerator;
import com.sundry.order.OrderSource;
import com.sundry.order.OrderStatus;
import com.sundry.seckill.dto.CreateSeckillOrderRequest;
import com.sundry.seckill.dto.SeckillResultView;
import com.sundry.seckill.dto.SeckillSubmitView;
import com.sundry.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 秒杀抢购：Redis Lua 预占后投递 Kafka，消费端幂等建单。
 * 用户通过 requestId 轮询结果；建单失败会回补 Redis 并写入失败原因。
 */
@Service
public class SeckillOrderService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderService.class);

    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final UserAddressMapper addressMapper;
    private final ObjectStorage storage;
    private final SeckillStockService stockService;
    private final SeckillResultService resultService;
    private final SeckillOrderPublisher publisher;
    private final OrderNoGenerator orderNoGenerator;
    private final TransactionTemplate transactionTemplate;

    public SeckillOrderService(SeckillActivityMapper activityMapper, OrderMapper orderMapper,
                               OrderItemMapper orderItemMapper, ProductMapper productMapper,
                               UserAddressMapper addressMapper, ObjectStorage storage,
                               SeckillStockService stockService, SeckillResultService resultService,
                               SeckillOrderPublisher publisher, OrderNoGenerator orderNoGenerator,
                               TransactionTemplate transactionTemplate) {
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.addressMapper = addressMapper;
        this.storage = storage;
        this.stockService = stockService;
        this.resultService = resultService;
        this.publisher = publisher;
        this.orderNoGenerator = orderNoGenerator;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 抢购入口：校验、Redis 预占、投递消息，立即返回 requestId。
     */
    public SeckillSubmitView submit(Long userId, CreateSeckillOrderRequest request) {
        SeckillActivity activity = requireRunningActivity(request.activityId());
        Product product = requireSaleableProduct(activity);

        Order existing = orderMapper.findSeckillOrder(userId, activity.getId());
        if (existing != null) {
            return successSubmit(userId, activity.getId(), existing.getOrderNo());
        }

        resolveAddress(userId, product, request.addressId());

        if (!stockService.ensureWarm(activity)) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        }
        int code = stockService.tryDeduct(activity.getId(), userId, activity.getPerUserLimit());
        if (code == SeckillStockService.LIMIT_REACHED) {
            throw new BusinessException(ErrorCode.SECKILL_LIMIT_REACHED);
        }
        if (code == SeckillStockService.SOLD_OUT) {
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (code != SeckillStockService.OK) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        }

        String requestId = UUID.randomUUID().toString();
        resultService.start(requestId, userId, activity.getId());
        try {
            publisher.publish(new SeckillOrderMessage(requestId, activity.getId(),
                    userId, request.addressId()));
        } catch (RuntimeException ex) {
            stockService.rollbackReservation(activity.getId(), userId, 1);
            resultService.fail(requestId, ErrorCode.SECKILL_NOT_READY.name(),
                    ErrorCode.SECKILL_NOT_READY.message());
            throw ex;
        }
        return new SeckillSubmitView(requestId, "PROCESSING");
    }

    /**
     * 消费端建单。重复消息、重复请求都按幂等处理。
     */
    public void process(SeckillOrderMessage message) {
        SeckillResultView current = resultService.get(message.requestId());
        if (current == null || !"PROCESSING".equals(current.status())) {
            return;
        }

        Long userId = message.userId();
        Long activityId = message.activityId();
        try {
            Order existing = orderMapper.findSeckillOrder(userId, activityId);
            if (existing != null) {
                resultService.success(message.requestId(), existing.getOrderNo());
                return;
            }

            SeckillActivity activity = requireRunningActivity(activityId);
            Product product = requireSaleableProduct(activity);
            UserAddress address = resolveAddress(userId, product, message.addressId());

            String orderNo = transactionTemplate.execute(status ->
                    doCreate(userId, activity, product, address));
            resultService.success(message.requestId(), orderNo);
        } catch (DuplicateKeyException ex) {
            Order duplicate = orderMapper.findSeckillOrder(userId, activityId);
            if (duplicate != null) {
                // 并发下另一条消息已建单，多扣的一份库存还回去
                stockService.restoreStock(activityId, 1);
                resultService.success(message.requestId(), duplicate.getOrderNo());
            } else {
                failAndRollback(message, ErrorCode.INTERNAL_ERROR);
            }
        } catch (BusinessException ex) {
            failAndRollback(message, ex.getErrorCode());
        } catch (RuntimeException ex) {
            log.error("秒杀建单失败 requestId={}", message.requestId(), ex);
            failAndRollback(message, ErrorCode.INTERNAL_ERROR);
        }
    }

    private SeckillSubmitView successSubmit(Long userId, Long activityId, String orderNo) {
        String requestId = UUID.randomUUID().toString();
        resultService.start(requestId, userId, activityId);
        resultService.success(requestId, orderNo);
        return new SeckillSubmitView(requestId, "SUCCESS");
    }

    private void failAndRollback(SeckillOrderMessage message, ErrorCode errorCode) {
        stockService.rollbackReservation(message.activityId(), message.userId(), 1);
        resultService.fail(message.requestId(), errorCode.name(), errorCode.message());
    }

    private String doCreate(Long userId, SeckillActivity activity, Product product,
                            UserAddress address) {
        if (activityMapper.incrementSold(activity.getId()) == 0) {
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (product.getType() == ProductType.PHYSICAL
                && productMapper.deductStock(product.getId(), 1) == 0) {
            throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
        }

        Order order = new Order();
        order.setOrderNo(orderNoGenerator.next());
        order.setActivityId(activity.getId());
        order.setOrderSource(OrderSource.SECKILL);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(activity.getSeckillPrice());
        order.setHasPhysical(product.getType() == ProductType.PHYSICAL ? 1 : 0);
        if (address != null) {
            order.setReceiverName(address.getReceiverName());
            order.setReceiverPhone(address.getReceiverPhone());
            order.setReceiverAddress(address.getProvince() + address.getCity()
                    + address.getDistrict() + address.getDetail());
        }
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setProductType(product.getType());
        item.setPrice(activity.getSeckillPrice());
        item.setQuantity(1);
        item.setCoverUrl(coverUrl(product));
        item.setDigitalFileId(product.getDigitalFileId());
        orderItemMapper.insertBatch(List.of(item));

        return order.getOrderNo();
    }

    private SeckillActivity requireRunningActivity(Long activityId) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null || !isRunning(activity)) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        }
        return activity;
    }

    private Product requireSaleableProduct(SeckillActivity activity) {
        Product product = productMapper.findById(activity.getProductId());
        if (product == null || product.getDeletedAt() != null
                || product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.SECKILL_PRODUCT_NOT_SALEABLE);
        }
        return product;
    }

    private UserAddress resolveAddress(Long userId, Product product, Long addressId) {
        if (product.getType() != ProductType.PHYSICAL) {
            return null;
        }
        if (addressId == null) {
            throw new BusinessException(ErrorCode.ADDRESS_REQUIRED);
        }
        UserAddress address = addressMapper.findByIdAndUser(addressId, userId);
        if (address == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        return address;
    }

    private boolean isRunning(SeckillActivity activity) {
        LocalDateTime now = LocalDateTime.now();
        return activity.getStatus() == SeckillStatus.ACTIVE
                && !now.isBefore(activity.getStartAt())
                && now.isBefore(activity.getEndAt());
    }

    private String coverUrl(Product product) {
        if (product.getCoverBucket() == null || product.getCoverObjectKey() == null) {
            return null;
        }
        return storage.publicUrl(product.getCoverBucket(), product.getCoverObjectKey());
    }
}
