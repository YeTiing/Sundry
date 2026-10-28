package com.sundry.assistant;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final String BUSY =
            "现在问的人有点多，稍后再试。你可以先看看：我的订单、购物车、商品列表。";

    private final AssistantService assistantService;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantBulkhead bulkhead;
    private final Duration waitTimeout;

    public AssistantController(AssistantService assistantService,
                               AssistantRateLimiter rateLimiter,
                               AssistantBulkhead bulkhead,
                               @Value("${app.assistant.wait-timeout:4s}") Duration waitTimeout) {
        this.assistantService = assistantService;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.waitTimeout = waitTimeout == null || waitTimeout.isNegative()
                ? Duration.ofSeconds(4) : waitTimeout;
    }

    /**
     * 异步返回：Tomcat 线程只负责提交任务，不等待模型。
     * 队列满或等待超时返回 200 + 降级文案，而不是 429/503。
     */
    @PostMapping("/chat")
    public DeferredResult<AssistantReply> chat(@Valid @RequestBody AssistantChatRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 限流放在进队列之前：被限流的请求不占用并发额度
        rateLimiter.check(user.id());

        DeferredResult<AssistantReply> deferred =
                new DeferredResult<>(waitTimeout.toMillis());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<AssistantReply> future =
                bulkhead.submit(() -> assistantService.chat(user, request, cancelled::get));
        deferred.onTimeout(() -> {
            cancelled.set(true);
            future.cancel(true);
            deferred.setResult(busyReply());
        });
        // 客户端断开或响应已结束时，不再让排队任务白跑模型
        deferred.onCompletion(() -> cancelled.set(true));
        future.whenComplete((reply, error) -> {
            if (deferred.isSetOrExpired()) {
                return;
            }
            if (error == null) {
                deferred.setResult(reply);
                return;
            }
            Throwable cause = unwrap(error);
            // 超时取消不是业务错误，统一按“忙”降级，不能落到全局 500
            if (cause instanceof AssistantBusyException
                    || cause instanceof CancellationException) {
                deferred.setResult(busyReply());
            } else {
                deferred.setErrorResult(cause);
            }
        });
        return deferred;
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private AssistantReply busyReply() {
        return new AssistantReply("clarify", BUSY, null, null, Map.of(),
                UUID.randomUUID().toString(), null);
    }
}
