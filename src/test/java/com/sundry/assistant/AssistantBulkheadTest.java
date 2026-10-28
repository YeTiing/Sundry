package com.sundry.assistant;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantBulkheadTest {

    private AssistantBulkhead bulkhead;

    @AfterEach
    void tearDown() {
        if (bulkhead != null) {
            bulkhead.shutdown();
        }
    }

    @Test
    void returnsReplyWhenCapacityAvailable() throws Exception {
        bulkhead = new AssistantBulkhead(2, 2, new SimpleMeterRegistry());

        AssistantReply reply = bulkhead.submit(() -> reply("ok")).get(1, TimeUnit.SECONDS);

        assertThat(reply.reply()).isEqualTo("ok");
    }

    @Test
    void degradesWhenQueueIsFull() throws Exception {
        bulkhead = new AssistantBulkhead(1, 1, new SimpleMeterRegistry());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            bulkhead.submit(() -> {
                started.countDown();
                release.await();
                return reply("first");
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            bulkhead.submit(() -> reply("second"));
            awaitQueueSize(1);

            CompletableFuture<AssistantReply> third = bulkhead.submit(() -> reply("third"));

            assertThatThrownBy(() -> third.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AssistantBusyException.class);
        } finally {
            release.countDown();
        }
    }

    private void awaitQueueSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            if (bulkhead.queuedCount() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(bulkhead.queuedCount()).isEqualTo(expected);
    }

    private AssistantReply reply(String text) {
        return new AssistantReply("clarify", text, null, null, Map.of(), "trace", null);
    }
}
