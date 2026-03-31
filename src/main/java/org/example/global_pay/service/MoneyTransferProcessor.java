package org.example.global_pay.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.AccountNotFoundException;
import org.example.global_pay.exception.CurrencyMismatchException;
import org.example.global_pay.exception.SelfTransferException;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MoneyTransferProcessor {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public void execute(TransferRequest request, Transaction transaction) {
        UUID fromId = request.getFromId();
        UUID toId = request.getToId();
        BigDecimal amount = request.getAmount();

        if (fromId.equals(toId)) {
            throw new SelfTransferException("Cannot transfer to the same account");
        }

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

        transaction.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(transaction);
        log.info("Transaction {} committed successfully", transaction.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction createPendingTransaction(TransferRequest request) {
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(request.getIdempotencyKey())
                .fromAccountId(request.getFromId())
                .toAccountId(request.getToId())
                .amountSent(request.getAmount())
                .amountReceived(request.getAmount())
                .exchangeRate(BigDecimal.ONE)
                .status(TransactionStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        return transactionRepository.save(tx);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsFailed(Transaction transaction, String reason) {
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(reason);
        transactionRepository.save(transaction);
    }
}
