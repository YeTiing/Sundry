package com.sundry.nl2sql;

public class Nl2sqlValidationException extends RuntimeException {

    private final String code;

    public Nl2sqlValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
