package com.sundry.seckill;

import com.sundry.order.Order;
import com.sundry.order.OrderMapper;
import com.sundry.seckill.dto.SeckillResultView;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 抢购结果暂存在 Redis：PROCESSING / SUCCESS / FAILED，前端按 requestId 轮询。
 */
@Service
public class SeckillResultService {

    private static final String KEY = "seckill:result:%s";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ORDER_NO = "orderNo";
    private static final String FIELD_ERROR_CODE = "errorCode";
    private static final String FIELD_ERROR_MESSAGE = "errorMessage";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_ACTIVITY_ID = "activityId";

    private final StringRedisTemplate redis;
    private final SeckillProperties properties;
    private final OrderMapper orderMapper;

    public SeckillResultService(StringRedisTemplate redis, SeckillProperties properties,
                                OrderMapper orderMapper) {
        this.redis = redis;
        this.properties = properties;
        this.orderMapper = orderMapper;
    }

    public void start(String requestId, Long userId, Long activityId) {
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_STATUS, "PROCESSING");
        values.put(FIELD_USER_ID, String.valueOf(userId));
        values.put(FIELD_ACTIVITY_ID, String.valueOf(activityId));
        String key = key(requestId);
        redis.opsForHash().putAll(key, values);
        redis.expire(key, properties.getResultTtl());
    }

    public void success(String requestId, String orderNo) {
        String key = key(requestId);
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_STATUS, "SUCCESS");
        values.put(FIELD_ORDER_NO, orderNo);
        redis.opsForHash().putAll(key, values);
    }

    public void fail(String requestId, String errorCode, String errorMessage) {
        String key = key(requestId);
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_STATUS, "FAILED");
        values.put(FIELD_ERROR_CODE, errorCode);
        values.put(FIELD_ERROR_MESSAGE, errorMessage);
        redis.opsForHash().putAll(key, values);
    }

    public SeckillResultView get(String requestId) {
        return read(requestId);
    }

    /**
     * 只能查询自己的抢购结果。
     */
    public SeckillResultView getForUser(String requestId, Long userId) {
        SeckillResultView result = read(requestId);
        if (result == null) {
            return null;
        }
        Object owner = redis.opsForHash().get(key(requestId), FIELD_USER_ID);
        if (owner == null || !String.valueOf(userId).equals(owner.toString())) {
            return null;
        }
        return result;
    }

    private SeckillResultView read(String requestId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(requestId));
        if (entries.isEmpty()) {
            return null;
        }
        SeckillResultView view = new SeckillResultView(requestId,
                string(entries.get(FIELD_STATUS)),
                string(entries.get(FIELD_ORDER_NO)),
                string(entries.get(FIELD_ERROR_CODE)),
                string(entries.get(FIELD_ERROR_MESSAGE)));
        if ("SUCCESS".equals(view.status())) {
            return view;
        }
        // 结果缓存可能写失败；DB 订单已存在时以 DB 为准，避免用户一直看到 PROCESSING
        return fromDb(requestId, entries, view);
    }

    private SeckillResultView fromDb(String requestId, Map<Object, Object> entries,
                                     SeckillResultView fallback) {
        Object userId = entries.get(FIELD_USER_ID);
        Object activityId = entries.get(FIELD_ACTIVITY_ID);
        if (userId == null || activityId == null) {
            return fallback;
        }
        try {
            Order order = orderMapper.findSeckillOrder(
                    Long.valueOf(userId.toString()), Long.valueOf(activityId.toString()));
            if (order != null) {
                return new SeckillResultView(requestId, "SUCCESS", order.getOrderNo(), null, null);
            }
        } catch (NumberFormatException ex) {
            return fallback;
        }
        return fallback;
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private String key(String requestId) {
        return KEY.formatted(requestId);
    }
}
