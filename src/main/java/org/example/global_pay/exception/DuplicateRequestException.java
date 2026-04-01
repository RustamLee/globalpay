package org.example.global_pay.exception;

public class DuplicateRequestException extends GlobalPayException {
    public DuplicateRequestException(String message) {
        super(message);
    }
}
