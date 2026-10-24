package com.sundry.seckill;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试用发布器：把消息留在内存里，由测试显式调用 consumer 处理，不依赖真实 Kafka。
 */
@Component
@Profile("test")
public class RecordingSeckillOrderPublisher implements SeckillOrderPublisher {

    private final List<SeckillOrderMessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    @Override
    public void publish(SeckillOrderMessage message) {
        if (failNext.getAndSet(false)) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        }
        messages.add(message);
    }

    public List<SeckillOrderMessage> messages() {
        return new ArrayList<>(messages);
    }

    public SeckillOrderMessage last() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public void failNextPublish() {
        failNext.set(true);
    }

    public void clear() {
        messages.clear();
        failNext.set(false);
    }
}
