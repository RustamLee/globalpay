package org.example.global_pay.service;

import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.domain.User;
import org.example.global_pay.exception.CurrencyMismatchException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
    @ServiceConnection // Эта аннотация САМА пропишет URL, user и password для Spring Data
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    @DisplayName("Should transfer money between accounts")
    void shouldTransferMoney() {
        // GIVEN
        User user1 = userRepository.save(User.builder().id(UUID.randomUUID()).email("est1@mail.com").build());
        User user2 = userRepository.save(User.builder().id(UUID.randomUUID()).email("test2@mail.com").build());

        Account from = Account.builder()
                .id(UUID.randomUUID())
                .userId(user1.getId())
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .build();
        Account to = Account.builder()
                .id(UUID.randomUUID())
                .userId(user2.getId())
                .balance(new BigDecimal("50.00"))
                .currency("USD")
                .build();

        accountRepository.save(from);
        accountRepository.save(to);

        //WHEN: We transfer $30 from the first account to the second account
        transferService.transfer(from.getId(), to.getId(), new BigDecimal("30.00"));

        // THEN: The first account should have $70 and the second account should have $80
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

        // WHEN & THEN
        assertThrows(SelfTransferException.class, () -> {
            transferService.transfer(accountId, accountId, new BigDecimal("10.00"));
        });
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when source account has insufficient funds")
    void shouldThrowExceptionWhenInsufficientFunds() {
        // GIVEN
        User sender = userRepository.save(User.builder().id(UUID.randomUUID()).email("sendler@test.com").build());
        User receiver = userRepository.save(User.builder().id(UUID.randomUUID()).email("receiver@test.com").build());
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        accountRepository.save(Account.builder()
                .id(fromId).userId(sender.getId()).balance(new BigDecimal("10.00")).currency("USD").version(null).build());
        accountRepository.save(Account.builder()
                .id(toId).userId(receiver.getId()).balance(new BigDecimal("50.00")).currency("USD").version(null).build());

        // WHEN & THEN
        assertThrows(InsufficientFundsException.class, () -> {
            transferService.transfer(fromId, toId, new BigDecimal("100.00"));
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

        accountRepository.save(Account.builder()
                .id(fromId).userId(sender.getId()).balance(new BigDecimal("10.00")).currency("BRL").version(null).build());
        accountRepository.save(Account.builder()
                .id(toId).userId(receiver.getId()).balance(new BigDecimal("50.00")).currency("USD").version(null).build());

        // WHEN & THEN
        assertThrows(CurrencyMismatchException.class, () -> {
            transferService.transfer(fromId, toId, new BigDecimal("5.00"));
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

        // WHEN: We start two threads that try to transfer $80 from the same account at the same time
        for (int i = 0; i < threadsCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for the signal to start
                    transferService.transfer(fromId, toId, new BigDecimal("10.00"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Conflict during transfer: ", e);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Signal all threads to start
        finishLatch.await(); // Wait for all threads to finish
        executor.shutdown();

        Account from = accountRepository.findById(fromId).orElseThrow();
        Account to = accountRepository.findById(toId).orElseThrow();
        log.info("Final balances - From: {}, To: {}", from.getBalance(), to.getBalance());
        log.info("Failure count: {}", failureCount.get());

        assertEquals(0, new BigDecimal("1000.00").compareTo(from.getBalance().add(to.getBalance())), "Money lost/created!");

        // Проверка 2: Никто не ушел в минус
        assertTrue(from.getBalance().compareTo(BigDecimal.ZERO) >= 0, "Negative balance!");

        // Проверка 3: Были хоть какие-то конфликты (иначе тест не про конкурентность)
        assertTrue(failureCount.get() > 0, "No race conditions happened - test is too weak!");
    }

    @AfterEach
    void cleanDbAfter() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

}
