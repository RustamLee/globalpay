package org.example.global_pay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.DuplicateRequestException;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StringRedisTemplate redisTemplate;
    private final MoneyTransferProcessor processor;

    private static final String IDEMPOTENCY_PREFIX = "transfer_idempotency:";

    @Retryable(
            retryFor = {org.springframework.orm.ObjectOptimisticLockingFailureException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void transfer(TransferRequest request) {
        String key = IDEMPOTENCY_PREFIX + request.getIdempotencyKey();

        Optional<Transaction> existing = transactionRepository
                .findByIdempotencyKey(request.getIdempotencyKey());

        if (existing.isPresent()) {
            Transaction tx = existing.get();
            if (tx.getStatus() == TransactionStatus.SUCCESS) {
                log.info("Idempotent replay for SUCCESSFUL key: {}", request.getIdempotencyKey());
                return;
            }
            log.info("Continuing PENDING transaction for key: {}", request.getIdempotencyKey());
        }

        Boolean isNewRequest = redisTemplate.opsForValue()
                .setIfAbsent(key, "PROCESSING", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isNewRequest) && existing.isEmpty()) {
            log.warn("Duplicate request detected for key: {}", request.getIdempotencyKey());
            throw new DuplicateRequestException("Duplicate transfer request with idempotency key: " + request.getIdempotencyKey());
        }
        Transaction tx = existing.orElseGet(() -> processor.createPendingTransaction(request));
        try {
            processor.execute(request, tx);
            redisTemplate.opsForValue().set(key, "COMPLETED", Duration.ofHours(24));
        } catch (
                Exception e) {
            if (!(e instanceof ObjectOptimisticLockingFailureException)) {
                processor.markAsFailed(tx, e.getMessage());
                redisTemplate.delete(key);
            }
            throw e;
        }
    }

    public Page<Transaction> getTransactions(UUID accountId, Pageable pageable) {
        return transactionRepository.findAllByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(accountId, accountId, pageable);
    }
}
