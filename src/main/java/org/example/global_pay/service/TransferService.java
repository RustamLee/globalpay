package org.example.global_pay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.AccountNotFoundException;
import org.example.global_pay.exception.CurrencyMismatchException;
import org.example.global_pay.exception.DuplicateRequestException;
import org.example.global_pay.exception.SelfTransferException;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String IDEMPOTENCY_PREFIX = "transfer_idempotency:";

    public void transfer(TransferRequest request) {

        String key = IDEMPOTENCY_PREFIX + request.getIdempotencyKey();

        Boolean isNewRequest = redisTemplate.opsForValue()
                .setIfAbsent(key, "PROCESSING", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isNewRequest)) {
            log.warn("Duplicate request detected for key: {}", request.getIdempotencyKey());
            throw new DuplicateRequestException("Request with this idempotency key is already being processed or completed");
        }

        try {
            performMoneyTransfer(request);
            redisTemplate.opsForValue().set(key, "COMPLETED", Duration.ofHours(24));
        } catch (Exception e) {
            redisTemplate.delete(key);
            throw e;
        }


    }

    @Transactional // Теперь транзакция только на работу с БД
    public void performMoneyTransfer(TransferRequest request) {
        UUID fromId = request.getFromId();
        UUID toId = request.getToId();
        BigDecimal amount = request.getAmount();

        log.info("Processing transfer from {} to {}", fromId, toId);

        if (fromId.equals(toId)) {
            throw new SelfTransferException("Cannot transfer to the same account");
        }

        // Блокировка и логика (Optimistic Locking сработает здесь)
        Account from = accountRepository.findById(fromId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + fromId));
        Account to = accountRepository.findById(toId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + toId));

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new CurrencyMismatchException("Currency mismatch");
        }

        from.debit(amount);
        to.credit(amount);

        accountRepository.save(from);
        accountRepository.save(to);

        Transaction transaction = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(request.getIdempotencyKey()) // ОБЯЗАТЕЛЬНО сохраняем ключ в БД
                .fromAccountId(fromId)
                .toAccountId(toId)
                .amountSent(amount)
                .amountReceived(amount)
                .status(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .exchangeRate(BigDecimal.ONE)
                .build();

        transactionRepository.save(transaction);
    }

    public Page<Transaction> getTransactions(UUID accountId, Pageable pageable) {
        return transactionRepository.findAllByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(accountId, accountId, pageable);
    }
}
