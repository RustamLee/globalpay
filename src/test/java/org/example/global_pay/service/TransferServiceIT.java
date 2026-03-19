package org.example.global_pay.service;

import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.User;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
public class TransferServiceIT {
    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldTransferMoney() {
        // CREARE TWO USERS
        User user1 = userRepository.save(new User(UUID.randomUUID(), "test1@mail.com", null));
        User user2 = userRepository.save(new User(UUID.randomUUID(), "test2@mail.com", null));


        // GIVEN
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

        assertEquals(new BigDecimal("70.00"), fromAccount.getBalance());
        assertEquals(new BigDecimal("80.00"), toAccount.getBalance());

    }
}
