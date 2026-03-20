package org.example.global_pay.exception;

public abstract class GlobalPayException extends RuntimeException {
    protected GlobalPayException(String message) {
        super(message);
    }
}
