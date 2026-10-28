package com.sundry.assistant;

/**
 * 助手并发保护触发：队列已满，或排队等待超过上限。
 * 这类拒绝不返回 429，由 Controller 转成降级回答，避免用户看到硬失败。
 */
public class AssistantBusyException extends RuntimeException {

    private final String reason;

    public AssistantBusyException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
