package org.example.global_pay.service.outbox;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.OutboxEvent;
import org.example.global_pay.domain.OutboxStatus;
import org.example.global_pay.domain.User;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.OutboxEventRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.example.global_pay.repository.UserRepository;
import org.example.global_pay.service.TransferService;
import org.example.global_pay.service.gateway.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;


@SpringBootTest(properties = {
        "app.scheduling.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
@Slf4j
public class OutboxWorkerIT {

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @SpyBean
    private PaymentGateway paymentGateway;

    @Autowired
    private OutboxWorker outboxWorker;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
        registry.circuitBreaker("paymentGatewayCB").reset();
        Mockito.reset(paymentGateway);
    }

    @Test
    @DisplayName("Happy Path: Event should be PROCESSED after successful gateway call")
    void shouldEventuallyProcessPaymentThroughOutbox() {
        // GIVEN

        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sender@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        accountRepository.save(Account.builder()
                .id(fromId)
                .userId(sender.getId())
                .balance(new BigDecimal("1000.00"))
                .currency("USD")
                .build());

        accountRepository.save(Account.builder()
                .id(toId)
                .userId(receiver.getId())
                .balance(new BigDecimal("0.00"))
                .currency("USD")
                .build());


        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        // WHEN
        transferService.transfer(request);
        outboxWorker.processOutbox();

        // THEN
        var events = outboxRepository.findAll();
        OutboxEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("Failure Path: Event should reach FAILED status after 5 unsuccessful attempts")
    void shouldMoveToFailedStatusAfterMaxAttempts() {
        // GIVEN
        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sender@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        accountRepository.save(Account.builder().id(fromId).userId(sender.getId()).balance(new BigDecimal("1000.00")).currency("USD").build());
        accountRepository.save(Account.builder().id(toId).userId(receiver.getId()).balance(new BigDecimal("0.00")).currency("USD").build());

        doThrow(new RuntimeException("Gateway Overload"))
                .when(paymentGateway).process(any());

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("10.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        // WHEN
        transferService.transfer(request);
        assertThat(outboxRepository.findAll()).hasSize(1);
        CircuitBreaker cb = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("paymentGatewayCB");
        cb.transitionToDisabledState();
        try {
            for (int i = 0; i < 5; i++) {
                forceRetryNow();
                outboxWorker.processOutbox();
            }
        } finally {
            cb.reset();
        }

        // THEN
        OutboxEvent event = outboxRepository.findAll().get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("Circuit Breaker Path: Should delay event without incrementing attempts when CB is OPEN")
    void shouldDelayEventWhenCircuitBreakerIsOpen() {
        // GIVEN
        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sender@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        accountRepository.save(Account.builder().id(fromId).userId(sender.getId()).balance(new BigDecimal("1000.00")).currency("USD").build());
        accountRepository.save(Account.builder().id(toId).userId(receiver.getId()).balance(new BigDecimal("0.00")).currency("USD").build());
        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("10.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();
        // WHEN
        transferService.transfer(request);
        CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
        CircuitBreaker cb = registry.circuitBreaker("paymentGatewayCB");
        try {
            cb.transitionToOpenState();
            outboxWorker.processOutbox();
        } finally {
            cb.transitionToClosedState();
        }
        OutboxEvent event = outboxRepository.findAll().get(0);

        // THEN
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextRetryAt()).isAfter(LocalDateTime.now().plusSeconds(5));

        verify(paymentGateway, never()).process(any());
    }

    private void forceRetryNow() {
        outboxRepository.findAll().forEach(event -> {
            event.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
            outboxRepository.save(event);
        });
    }
}
