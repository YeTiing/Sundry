package com.sundry.seckill;

public interface SeckillOrderPublisher {

    void publish(SeckillOrderMessage message);
}
