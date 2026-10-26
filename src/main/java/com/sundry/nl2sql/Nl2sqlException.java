package com.sundry.nl2sql;

public class Nl2sqlException extends RuntimeException {

    public Nl2sqlException(String message) {
        super(message);
    }

    public Nl2sqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
