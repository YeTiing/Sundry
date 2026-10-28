package com.sundry.assistant;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 助手审计写入：默认异步，避免每条消息都占一个数据库连接。
 * 队列满时用 CallerRunsPolicy 退化为同步写入，保证审计不丢。
 * 测试 profile 关闭异步，保持用例断言简单。
 */
@Component
public class AssistantAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AssistantAuditWriter.class);

    private final AssistantChatLogMapper logMapper;
    private final boolean async;
    private final ThreadPoolExecutor executor;

    public AssistantAuditWriter(
            AssistantChatLogMapper logMapper,
            @Value("${app.assistant.audit-async:true}") boolean async,
            @Value("${app.assistant.audit-queue-capacity:1000}") int queueCapacity) {
        this.logMapper = logMapper;
        this.async = async;
        this.executor = async ? new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "assistant-audit");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()) : null;
    }

    public void write(AssistantChatLog entry) {
        if (!async) {
            insert(entry);
            return;
        }
        executor.execute(() -> insert(entry));
    }

    private void insert(AssistantChatLog entry) {
        try {
            logMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("助手审计写入失败 userId={} action={}",
                    entry.getUserId(), entry.getAction(), ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
