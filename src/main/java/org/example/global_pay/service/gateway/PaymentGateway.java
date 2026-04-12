package org.example.global_pay.service.gateway;

import org.example.global_pay.dto.GatewayResponse;
import org.example.global_pay.dto.PaymentProviderRequest;

public interface PaymentGateway {
    GatewayResponse process(PaymentProviderRequest request);
}
