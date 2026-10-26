package com.sundry.assistant;

/**
 * 工具结果：reply 给接口兜底，data 给前端做结构化渲染。
 */
public record AssistantToolResult(String reply, Object data) {

    public AssistantToolResult(String reply) {
        this(reply, null);
    }
}
