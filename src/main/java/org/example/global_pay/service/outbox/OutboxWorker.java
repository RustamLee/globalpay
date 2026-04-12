package org.example.global_pay.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.OutboxEvent;
import org.example.global_pay.domain.OutboxStatus;
import org.example.global_pay.dto.PaymentProviderRequest;
import org.example.global_pay.repository.OutboxEventRepository;
import org.example.global_pay.service.gateway.PaymentGateway;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxWorker {
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentGateway paymentGateway;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findPendingToProcess(
                LocalDateTime.now(),
                PageRequest.of(0, 10)
        );

        if (events.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events to process", events.size());

        for (OutboxEvent event : events) {
            processEvent(event);
        }
    }

    private void processEvent(OutboxEvent event) {
        try {
            PaymentProviderRequest request = objectMapper.readValue(
                    event.getPayload(),
                    PaymentProviderRequest.class
            );

            paymentGateway.process(request);

            event.setStatus(OutboxStatus.PROCESSED);
            log.info("Event {} processed successfully", event.getId());

        } catch (Exception e) {
            log.error("Failed to process outbox event {}: {}", event.getId(), e.getMessage());
            event.setAttempts(event.getAttempts() + 1);
        } finally {
            outboxEventRepository.save(event);
        }
    }
}
