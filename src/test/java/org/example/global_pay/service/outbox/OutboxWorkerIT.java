package org.example.global_pay.service.outbox;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.*;
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
import org.mockserver.client.MockServerClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;


@SpringBootTest(properties = {
        "app.scheduling.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
@Slf4j
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Container
    static MockServerContainer mockServer = new MockServerContainer(
            DockerImageName.parse("mockserver/mockserver:5.15.0")
    );

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);


    private MockServerClient mockClient;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine")
    );

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.gateway.url", () -> "http://" + mockServer.getHost() + ":" + mockServer.getServerPort());
    }

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
        mockClient = new MockServerClient(mockServer.getHost(), mockServer.getServerPort());
        mockClient.reset();
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

        mockClient.when(request().withMethod("POST"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\",\"gatewayReference\":\"MOCK-REF-123\"}"));

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
        assertThat(event.getLastError()).isEqualTo("Gateway Overload");
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

    @Test
    @DisplayName("Should recover and process events after gateway comes back online via MockServer")
    void shouldRecoverAfterGatewayFailure() {
        // 1. Arrange
        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sender@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        accountRepository.save(Account.builder().id(fromId).userId(sender.getId()).balance(new BigDecimal("1000.00")).currency("USD").build());
        accountRepository.save(Account.builder().id(toId).userId(receiver.getId()).balance(new BigDecimal("0.00")).currency("USD").build());

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId).toId(toId).amount(new BigDecimal("10.00"))
                .idempotencyKey(UUID.randomUUID()).build();

        Mockito.reset(paymentGateway);

        mockClient.when(request().withMethod("POST"))
                .respond(response().withStatusCode(500));

        transferService.transfer(request);
        outboxWorker.processOutbox();

        OutboxEvent event = outboxRepository.findAll().get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);

        mockClient.reset();
        mockClient.when(request().withMethod("POST"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"externalTransactionId\":\"tx\",\"status\":\"SUCCESS\",\"gatewayReference\":\"REC-123\"}"));

        forceRetryNow();
        outboxWorker.processOutbox();

        OutboxEvent eventAfter = outboxRepository.findAll().get(0);
        assertThat(eventAfter.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(eventAfter.getLastError()).isNull();
    }


}
