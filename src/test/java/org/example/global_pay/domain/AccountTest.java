package org.example.global_pay.domain;

import org.example.global_pay.exception.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AccountTest {

    @Test
    @DisplayName("Debit should decrease balance when enough funds")
    void debitShouldDecreaseBalance() {
        // GIVEN:
        Account account = Account.builder()
                .balance(new BigDecimal("100.00"))
                .build();

        // WHEN
        account.debit(new BigDecimal("30.00"));

        // THEN
        assertEquals(new BigDecimal("70.00"), account.getBalance());
    }

    @Test
    @DisplayName("Debit should throw exception when insufficient funds")
    void debitShouldThrowException() {
        Account account = Account.builder().balance(new BigDecimal("10.00")).build();

        assertThrows(InsufficientFundsException.class, () -> {
            account.debit(new BigDecimal("100.00"));
        });
    }

}
