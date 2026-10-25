package com.sundry.feed;

public class MilvusException extends RuntimeException {

    public MilvusException(String message) {
        super(message);
    }

    public MilvusException(String message, Throwable cause) {
        super(message, cause);
    }
}
