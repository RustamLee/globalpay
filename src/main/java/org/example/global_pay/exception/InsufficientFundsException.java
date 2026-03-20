package org.example.global_pay.exception;

public class InsufficientFundsException extends GlobalPayException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
