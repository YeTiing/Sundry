package com.sundry.assistant;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantControllerTest {

    @Test
    void cancelledFutureBecomesBusyReplyInsteadOf500() {
        AssistantService service = mock(AssistantService.class);
        AssistantRateLimiter rateLimiter = mock(AssistantRateLimiter.class);
        AssistantBulkhead bulkhead = mock(AssistantBulkhead.class);
        when(bulkhead.submit(any())).thenReturn(
                CompletableFuture.failedFuture(new CancellationException("timeout")));
        AssistantController controller = new AssistantController(
                service, rateLimiter, bulkhead, Duration.ofSeconds(4));

        DeferredResult<AssistantReply> result = controller.chat(
                new AssistantChatRequest(null, "你好"),
                new AuthenticatedUser(1L, Role.USER));

        assertThat(result.getResult()).isInstanceOf(AssistantReply.class);
        AssistantReply reply = (AssistantReply) result.getResult();
        assertThat(reply.action()).isEqualTo("clarify");
        assertThat(reply.reply()).contains("问的人有点多");
    }
}
