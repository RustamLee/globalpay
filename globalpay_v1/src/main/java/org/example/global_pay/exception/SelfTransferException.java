package org.example.global_pay.exception;

public class SelfTransferException extends GlobalPayException {
    public SelfTransferException(String message) {
        super(message);
    }
}
