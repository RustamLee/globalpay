package org.example.global_pay.service;

import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.domain.User;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.CurrencyMismatchException;
import org.example.global_pay.exception.DuplicateRequestException;
import org.example.global_pay.exception.InsufficientFundsException;
import org.example.global_pay.exception.SelfTransferException;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.example.global_pay.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
public class TransferServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanDbAfter() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

    }

    @Test
    @DisplayName("Should transfer money between accounts")
    void shouldTransferMoney() {
        // GIVEN
        User user1 = userRepository.save(User.builder().id(UUID.randomUUID()).email("est1@mail.com").build());
        User user2 = userRepository.save(User.builder().id(UUID.randomUUID()).email("test2@mail.com").build());

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        Account from = Account.builder()
                .id(fromId)
                .userId(user1.getId())
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .build();
        Account to = Account.builder()
                .id(toId)
                .userId(user2.getId())
                .balance(new BigDecimal("50.00"))
                .currency("USD")
                .build();

        accountRepository.save(from);
        accountRepository.save(to);

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("30.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        //WHEN:
        transferService.transfer(request);

        // THEN:
        Account fromAccount = accountRepository.findById(from.getId()).orElseThrow();
        Account toAccount = accountRepository.findById(to.getId()).orElseThrow();

        assertThat(fromAccount.getBalance()).isEqualByComparingTo("70.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("80.00");

        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    @DisplayName("Should throw SelfTransferException when source and destination are the same")
    void shouldThrowExceptionWhenSelfTransfer() {
        // GIVEN
        User user = userRepository.save(User.builder().id(UUID.randomUUID()).email("self@test.com").build());
        UUID accountId = UUID.randomUUID();
        accountRepository.save(Account.builder()
                .id(accountId)
                .userId(user.getId())
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .version(null)
                .build());

        TransferRequest request = TransferRequest.builder()
                .fromId(accountId)
                .toId(accountId)
                .amount(new BigDecimal("10.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        // WHEN & THEN
        assertThrows(SelfTransferException.class, () -> {
            transferService.transfer(request);
        });
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when source account has insufficient funds")
    void shouldThrowExceptionWhenInsufficientFunds() {
        // GIVEN

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sendler@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());

        accountRepository.save(Account.builder()
                .id(fromId).userId(sender.getId()).balance(new BigDecimal("10.00")).currency("USD").version(null).build());
        accountRepository.save(Account.builder()
                .id(toId).userId(receiver.getId()).balance(new BigDecimal("50.00")).currency("USD").version(null).build());

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        // WHEN & THEN
        assertThrows(InsufficientFundsException.class, () -> {
            transferService.transfer(request);
        });
    }

    @Test
    @DisplayName("Should throw CurrencyMismatchException when accounts have different currencies")
    void shouldThrowExceptionWhenCurrencyMismatch() {
        // GIVEN
        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sender@test").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test").build());
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("5.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        accountRepository.save(Account.builder()
                .id(fromId).userId(sender.getId()).balance(new BigDecimal("10.00")).currency("BRL").version(null).build());
        accountRepository.save(Account.builder()
                .id(toId).userId(receiver.getId()).balance(new BigDecimal("50.00")).currency("USD").version(null).build());

        // WHEN & THEN
        assertThrows(CurrencyMismatchException.class, () -> {
            transferService.transfer(request);
        });
    }

    @Test
    @DisplayName("Should handle concurrent transfers safely with Optimistic Locking")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldHandleConcurrentTransfers() throws InterruptedException {
        // GIVEN
        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sender@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        accountRepository.save(Account.builder()
                .id(fromId).userId(sender.getId()).balance(new BigDecimal("1000.00")).currency("USD").build());
        accountRepository.save(Account.builder()
                .id(toId).userId(receiver.getId()).balance(new BigDecimal("0.00")).currency("USD").build());

        int threadsCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadsCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("10.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        // WHEN:
        for (int i = 0; i < threadsCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Conflict during transfer: ", e);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        Account from = accountRepository.findById(fromId).orElseThrow();
        Account to = accountRepository.findById(toId).orElseThrow();
        log.info("Final balances - From: {}, To: {}", from.getBalance(), to.getBalance());
        log.info("Failure count: {}", failureCount.get());

        assertEquals(0, new BigDecimal("1000.00").compareTo(from.getBalance().add(to.getBalance())), "Money lost/created!");

        assertTrue(from.getBalance().compareTo(BigDecimal.ZERO) >= 0, "Negative balance!");

        assertTrue(failureCount.get() > 0, "No race conditions happened - test is too weak!");
    }

    @Test
    @DisplayName("Should process transfer only once for the same idempotency key in real Redis")
    void shouldHandleIdempotencyRealLife() {
        // GIVEN:
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("20.00"))
                .idempotencyKey(key)
                .build();

        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sendler@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());

        accountRepository.save(Account.builder()
                .id(fromId).userId(sender.getId()).balance(new BigDecimal("100.00")).currency("USD").build());
        accountRepository.save(Account.builder()
                .id(toId).userId(receiver.getId()).balance(new BigDecimal("50.00")).currency("USD").build());

        // WHEN:
        transferService.transfer(request);

        // THEN:
        assertThrows(DuplicateRequestException.class, () -> {
            transferService.transfer(request);
        });

        Account from = accountRepository.findById(fromId).orElseThrow();
        assertThat(from.getBalance()).isEqualByComparingTo("80.00"); // 100 - 20

        long count = transactionRepository.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals(key))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return paginated transaction history for account")
    void shouldReturnHistory() {

        // GIVEN
        User user3 = userRepository.save(User.builder().id(UUID.randomUUID()).email("test3@mail.com").build());
        User user4 = userRepository.save(User.builder().id(UUID.randomUUID()).email("test4@mail.com").build());

        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        Account from = Account.builder()
                .id(fromId)
                .userId(user3.getId())
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .build();
        Account to = Account.builder()
                .id(toId)
                .userId(user4.getId())
                .balance(new BigDecimal("50.00"))
                .currency("USD")
                .build();

        accountRepository.save(from);
        accountRepository.save(to);

        TransferRequest request1 = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("10.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        TransferRequest request2 = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("15.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();
        TransferRequest request3 = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("5.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        //WHEN
        transferService.transfer(request1);
        transferService.transfer(request2);
        transferService.transfer(request3);

        // THEN
        Page<Transaction> history = transferService.getTransactions(fromId, PageRequest.of(0, 10));        // THEN
        assertThat(history.getContent()).hasSize(3);
    }

}
