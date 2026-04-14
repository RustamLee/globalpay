package org.example.global_pay.service.gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.dto.GatewayResponse;
import org.example.global_pay.dto.PaymentProviderRequest;
import org.example.global_pay.exception.GatewayException;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Component
@Slf4j
public class FakeExternalGateway implements PaymentGateway {

    private final RestTemplate restTemplate;
    private final String gatewayUrl;

    public FakeExternalGateway(RestTemplateBuilder builder,
                               @Value("${app.gateway.url}") String gatewayUrl) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .build();
        this.gatewayUrl = gatewayUrl;
    }

    @Override
    @CircuitBreaker(name = "paymentGatewayCB")
    public GatewayResponse process(PaymentProviderRequest request) {
        log.info("Calling external gateway at: {}", gatewayUrl);

        try {
            return restTemplate.postForObject(gatewayUrl + "/v1/payments", request, GatewayResponse.class);

        } catch (Exception e) {
            log.error("Gateway call failed for tx {}: {}", request.externalTransactionId(), e.getMessage());
            throw new GatewayException("Gateway error: " + e.getMessage());
        }
    }

}
