package org.example.global_pay.service;

import lombok.RequiredArgsConstructor;
import org.example.global_pay.domain.Account;
import org.example.global_pay.exception.AccountNotFoundException;
import org.example.global_pay.exception.CurrencyMismatchException;
import org.example.global_pay.exception.SelfTransferException;
import org.example.global_pay.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;

    @Transactional
    public void transfer(UUID fromId, UUID toId, BigDecimal amount) {

        if (fromId.equals(toId)) {
            throw new SelfTransferException("Cannot transfer to the same account");
        }

        Account from = accountRepository.findById(fromId).orElseThrow(() -> new AccountNotFoundException("Account not found: " + fromId));
        Account to = accountRepository.findById(toId).orElseThrow(() -> new AccountNotFoundException("Account not found: " + toId));
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new CurrencyMismatchException("Currency mismatch: " + from.getCurrency() + " and " + to.getCurrency());
        }
        from.debit(amount);
        to.credit(amount);
        accountRepository.save(from);
        accountRepository.save(to);

    }

}
