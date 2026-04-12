package org.example.global_pay.service.outbox;

import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.OutboxStatus;
import org.example.global_pay.domain.User;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.OutboxEventRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.example.global_pay.repository.UserRepository;
import org.example.global_pay.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;

import static org.assertj.core.api.Assertions.assertThat;


import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public class OutboxIT {

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

    @BeforeEach
    void setUpDB() {
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
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

        // THEN
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var events = outboxRepository.findAll();
                    boolean processed = events.stream()
                            .filter(e -> e.getPayload().contains(fromId.toString()))
                            .anyMatch(e -> e.getStatus() == OutboxStatus.PROCESSED);
                    assertThat(processed).isTrue();
                });
    }

}
