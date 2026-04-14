package org.example.global_pay.service.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.global_pay.domain.OutboxStatus;
import org.example.global_pay.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetricsService {
    private final OutboxEventRepository repository;

    public OutboxMetricsService(OutboxEventRepository repository, MeterRegistry registry) {
        this.repository = repository;
        Gauge.builder("payment.outbox.queue.size",  repository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Current number of pending outbox events")
                .register(registry);

    }

}
