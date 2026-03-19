package org.example.global_pay.service;

import lombok.RequiredArgsConstructor;
import org.example.global_pay.domain.Account;
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

        Account from = accountRepository.findById(fromId).orElseThrow(() -> new RuntimeException("Sender account not found"));
        Account to = accountRepository.findById(toId).orElseThrow(() -> new RuntimeException("Receiver account not found"));

        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));

        accountRepository.save(from);
        accountRepository.save(to);

    }

}
