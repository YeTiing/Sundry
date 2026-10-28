package com.sundry.assistant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 助手并发闸门：固定并发 + 有界短队列。
 *
 * 提交后立即返回 CompletableFuture，Tomcat 线程不被模型调用占住；
 * 等待上限和降级由 Controller 的 DeferredResult 负责。
 */
@Component
public class AssistantBulkhead {

    private static final Logger log = LoggerFactory.getLogger(AssistantBulkhead.class);

    private final ThreadPoolExecutor executor;
    private final MeterRegistry meterRegistry;

    public AssistantBulkhead(
            @Value("${app.assistant.concurrency:32}") int concurrency,
            @Value("${app.assistant.queue-capacity:200}") int queueCapacity,
            MeterRegistry meterRegistry) {
        int threads = Math.max(1, concurrency);
        int capacity = Math.max(1, queueCapacity);
        this.meterRegistry = meterRegistry;
        // core = max：固定并发，避免「先塞满队列再扩线程」的反直觉行为
        this.executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "assistant-call");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());

        Gauge.builder("sundry.assistant.bulkhead.active", executor,
                        ThreadPoolExecutor::getActiveCount)
                .description("正在执行的助手调用数")
                .register(meterRegistry);
        Gauge.builder("sundry.assistant.bulkhead.queued", executor,
                        pool -> pool.getQueue().size())
                .description("排队中的助手调用数")
                .register(meterRegistry);
    }

    public CompletableFuture<AssistantReply> submit(Callable<AssistantReply> task) {
        CompletableFuture<AssistantReply> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new AssistantException("TASK_FAILED", ex);
                }
            }, executor);
        } catch (RejectedExecutionException ex) {
            record("busy");
            log.warn("助手队列已满，降级");
            return CompletableFuture.failedFuture(new AssistantBusyException("QUEUE_FULL"));
        }

        future.whenComplete((reply, error) -> {
            if (error == null) {
                record("ok");
            } else if (error instanceof CancellationException) {
                record("cancelled");
            } else {
                record("error");
            }
        });
        return future;
    }

    private void record(String result) {
        Counter.builder("sundry.assistant.bulkhead")
                .tag("result", result)
                .description("助手并发闸门结果")
                .register(meterRegistry)
                .increment();
    }

    int activeCount() {
        return executor.getActiveCount();
    }

    int queuedCount() {
        return executor.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
