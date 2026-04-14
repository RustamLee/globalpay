package org.example.global_pay.exception;

public class GatewayException extends RuntimeException {
    public GatewayException(String message) {
        super(message);
    }
}
