package com.sundry.ai;

/**
 * AI 调用失败。调用方只需要知道"降级"，不需要区分底层原因。
 */
public class AiFeedException extends RuntimeException {

    public AiFeedException(String message) {
        super(message);
    }

    public AiFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
