package org.example.global_pay.domain;

import org.example.global_pay.exception.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("Credit should increase balance")
    void creditShouldIncreaseBalance() {
        Account account = Account.builder().balance(new BigDecimal("50.00")).build();
        account.credit(new BigDecimal("25.00"));
        assertThat(account.getBalance()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("Credit should throw exception when amount is negative")
    void creditShouldThrowException() {
        Account account = Account.builder().balance(new BigDecimal("50.00")).build();

        assertThrows(IllegalArgumentException.class, () -> {
            account.credit(new BigDecimal("-10.00"));
        });
    }

    @Test
    @DisplayName("Credit should throw exception when amount is zero")
    void creditShouldThrowExceptionWhenAmountIsZero() {
        Account account = Account.builder().balance(new BigDecimal("50.00")).build();

        assertThrows(IllegalArgumentException.class, () -> {
            account.credit(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Debit should throw exception when amount is negative")
    void debitShouldThrowExceptionWhenAmountIsNegative() {
        Account account = Account.builder().balance(new BigDecimal("50.00")).build();
        assertThrows(IllegalArgumentException.class, () -> {
            account.debit(new BigDecimal("-10.00"));
        });
    }

    @Test
    @DisplayName("Debit should throw exception when amount is zero")
    void debitShouldThrowExceptionWhenAmountIsZero() {
        Account account = Account.builder().balance(new BigDecimal("50.00")).build();
        assertThrows(IllegalArgumentException.class, () -> {
            account.debit(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Debit should allow withdrawing exact balance")
    void debitShouldAllowWithdrawingExactBalance() {
        Account account = Account.builder().balance(new BigDecimal("50.00")).build();
        account.debit(new BigDecimal("50.00"));
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }


}
