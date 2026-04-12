package org.example.global_pay.service.gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.GatewayStatus;
import org.example.global_pay.dto.GatewayResponse;
import org.example.global_pay.dto.PaymentProviderRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class FakeExternalGateway implements PaymentGateway {

    @Override
    @CircuitBreaker(name = "paymentGatewayCB")
    public GatewayResponse process(PaymentProviderRequest request) {
        log.info("Processing external payment: {}", request.externalTransactionId());
        simulateLatency();
        simulateRandomFailure();
        String gatewayRef = "GWP-" + UUID.randomUUID().toString().substring(0, 8);

        return new GatewayResponse(
                request.externalTransactionId(),
                GatewayStatus.SUCCESS,
                gatewayRef
        );
    }

    private void simulateLatency() {
        try {
            long latency = 500 + (long) (Math.random() * 2500);
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateRandomFailure() {
        if (Math.random() < 0.2) {
            log.error("External gateway is down (Simulated failure)");
            throw new RuntimeException("Gateway Timeout / Service Unavailable");
        }
    }

}
