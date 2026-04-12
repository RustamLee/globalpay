package org.example.global_pay.dto;

import java.math.BigDecimal;

public record PaymentProviderRequest(
        String externalTransactionId,
        BigDecimal amount,
        String currency,
        String destinationAccount,
        String description
) {
}
