package org.example.global_pay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.exception.AccountNotFoundException;
import org.example.global_pay.exception.CurrencyMismatchException;
import org.example.global_pay.exception.SelfTransferException;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public void transfer(UUID fromId, UUID toId, BigDecimal amount) {
        log.info("Initiating transfer of {} from account {} to account {}", amount, fromId, toId);

        if (fromId.equals(toId)) {
            log.error("Failed self-transfer for account {}", fromId);
            throw new SelfTransferException("Cannot transfer to the same account");
        }

        Account from = accountRepository.findById(fromId)
                .orElseThrow(() -> {
                    log.error("Account not found: {}", fromId);
                    return new AccountNotFoundException("Account not found: " + fromId);
                });
        Account to = accountRepository.findById(toId)
                .orElseThrow(() -> {
                    log.error("Account not found: {}", toId);
                    return new AccountNotFoundException("Account not found: " + toId);
                });
        if (!from.getCurrency().equals(to.getCurrency())) {
            log.error("Currency mismatch between accounts {} and {}", fromId, toId);
            throw new CurrencyMismatchException("Currency mismatch: " + from.getCurrency() + " and " + to.getCurrency());
        }
        from.debit(amount);
        to.credit(amount);
        accountRepository.save(from);
        accountRepository.save(to);

        Transaction transaction = Transaction.builder()
                .id(UUID.randomUUID())
                .fromAccountId(fromId)
                .toAccountId(toId)
                .amountSent(amount)
                .amountReceived(amount)
                .exchangeRate(BigDecimal.ONE)
                .status(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();
        transactionRepository.save(transaction);

        log.info("Transfer of {} from account {} to account {} completed successfully", amount, fromId, toId);

    }

}
