package com.sundry.assistant;

/**
 * 工具参数或执行失败；code 入库审计，reply 直接展示给用户。
 */
public class AssistantToolException extends RuntimeException {

    private final String code;
    private final String reply;

    public AssistantToolException(String code, String reply) {
        super(reply);
        this.code = code;
        this.reply = reply;
    }

    public String getCode() {
        return code;
    }

    public String getReply() {
        return reply;
    }
}
