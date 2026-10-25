package com.sundry.assistant;

public class AssistantException extends RuntimeException {

    public AssistantException(String message) {
        super(message);
    }

    public AssistantException(String message, Throwable cause) {
        super(message, cause);
    }
}
