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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;


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
            event.setLastError(null);
            log.info("Event {} processed successfully", event.getId());
        } catch (CallNotPermittedException e) {
            log.warn("Circuit Breaker is OPEN. Skipping event {} for now.", event.getId());
            event.setNextRetryAt(LocalDateTime.now().plusSeconds(10));
        } catch (Exception e) {
            log.error("Failed to process outbox event {}: {}", event.getId(), e.getMessage());
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            event.setLastError(errorMessage);
            if (attempts >= 5) {
                event.setStatus(OutboxStatus.FAILED);
                log.error("Event {} marked as FAILED after {} attempts", event.getId(), attempts);
            } else {
                LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(calculateDelay(attempts));
                event.setNextRetryAt(nextRetry);
                log.info("Event {} will be retried at {}", event.getId(), nextRetry);
            }
        } finally {
            outboxEventRepository.save(event);
        }
    }

    private long calculateDelay(int attempts) {
        return switch (attempts) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 15;
            default -> 60;
        };
    }
}
