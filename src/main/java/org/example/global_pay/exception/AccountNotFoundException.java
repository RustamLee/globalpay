package org.example.global_pay.exception;

public class AccountNotFoundException extends GlobalPayException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
