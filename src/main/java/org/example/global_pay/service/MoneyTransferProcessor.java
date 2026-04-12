package org.example.global_pay.service;


import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.example.global_pay.repository.OutboxEventRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MoneyTransferProcessor {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public void execute(TransferRequest request, Transaction transaction) {
        UUID fromId = request.getFromId();
        UUID toId = request.getToId();
        BigDecimal amount = request.getAmount();

        if (fromId.equals(toId)) {
            throw new SelfTransferException("Cannot transfer to the same account");
        }

        Account from = accountRepository.findByIdForUpdate(fromId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + fromId));
        Account to = accountRepository.findByIdForUpdate(toId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + toId));

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new CurrencyMismatchException("Currency mismatch");
        }

        from.debit(amount);
        to.credit(amount);

        accountRepository.save(from);
        accountRepository.save(to);

        transaction.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(transaction);
        log.info("Transaction {} committed successfully", transaction.getId());
    }

    public void executeWithLock(TransferRequest request, Transaction transaction) {
        if (transaction.getStatus() == TransactionStatus.SUCCESS) {
            return;
        }

        if (transaction.getStatus() == TransactionStatus.FAILED) {
            throw new DuplicateRequestException("Transfer already failed for idempotency key: " + request.getIdempotencyKey());
        }

        if (transaction.getStatus() == TransactionStatus.PROCESSING) {
            throw new DuplicateRequestException("Transfer is already in progress for idempotency key: " + request.getIdempotencyKey());
        }

        transaction.setStatus(TransactionStatus.PROCESSING);
        transactionRepository.save(transaction);

        execute(request, transaction);
    }

    public void markAsFailed(Transaction transaction, String reason) {
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(reason);
        transactionRepository.save(transaction);
    }
}
