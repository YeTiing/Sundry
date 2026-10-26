package com.sundry.ai;

/**
 * 触发一次 AI 重排构建的原因。
 * COLD_START 使用首访保底额度；REFILL 只使用共享额度。
 */
public enum BuildReason {
    COLD_START,
    REFILL
}
