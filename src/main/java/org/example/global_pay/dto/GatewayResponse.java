package org.example.global_pay.dto;

import org.example.global_pay.domain.GatewayStatus;

public record GatewayResponse(
        String externalTransactionId,
        GatewayStatus status,
        String gatewayReference
) {
}
