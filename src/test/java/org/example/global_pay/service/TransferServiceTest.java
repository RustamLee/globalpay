package org.example.global_pay.service;

import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.exception.AccountNotFoundException;
import org.example.global_pay.exception.CurrencyMismatchException;
import org.example.global_pay.exception.InsufficientFundsException;
import org.example.global_pay.exception.SelfTransferException;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {
    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransferService transferService;


    @Test
    @DisplayName("Should successfully transfer money between accounts")
    void shouldTransferMoneyBetweenAccounts() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        Account fromAccount = Account.builder()
                .id(fromId)
                .balance(new BigDecimal("200.00"))
                .currency("USD")
                .build();
        Account toAccount = Account.builder()
                .id(toId)
                .balance(new BigDecimal("50.00"))
                .currency("USD")
                .build();

        when(accountRepository.findById(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(toAccount));

        // WHEN
        transferService.transfer(fromId, toId, amount);

        // THEN
        assertThat(fromAccount.getBalance()).isEqualByComparingTo("100.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("150.00");


        verify(accountRepository, times(1)).save(fromAccount);
        verify(accountRepository, times(1)).save(toAccount);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Should throw SelfTransferException when fromId equals toId")
    void shouldThrowSelfTransferException() {
        // GIVEN
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        // WHEN & THEN
        assertThrows(SelfTransferException.class, () -> {
            transferService.transfer(accountId, accountId, amount);
        });

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException when source account is missing")
    void shouldThrowAccountNotFoundExceptionForSource() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        when(accountRepository.findById(fromId)).thenReturn(Optional.empty());

        // WHEN & THEN
        assertThrows(AccountNotFoundException.class, () -> {
            transferService.transfer(fromId, toId, amount);
        });

        verify(accountRepository, times(1)).findById(fromId);
        verify(accountRepository, never()).findById(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException when destination account is missing")
    void shouldThrowAccountNotFoundExceptionForDestination() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        Account fromAccount = Account.builder()
                .id(fromId)
                .balance(new BigDecimal("200.00"))
                .currency("USD")
                .build();

        when(accountRepository.findById(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toId)).thenReturn(Optional.empty());

        // WHEN & THEN
        assertThrows(AccountNotFoundException.class, () -> {
            transferService.transfer(fromId, toId, amount);
        });

        verify(accountRepository, times(1)).findById(fromId);
        verify(accountRepository, times(1)).findById(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw CurrencyMismatchException when currencies differ")
    void shouldThrowCurrencyMismatchException() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        Account fromAccount = Account.builder()
                .id(fromId)
                .balance(new BigDecimal("200.00"))
                .currency("USD")
                .build();
        Account toAccount = Account.builder()
                .id(toId)
                .balance(new BigDecimal("50.00"))
                .currency("EUR")
                .build();

        when(accountRepository.findById(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(toAccount));

        // WHEN & THEN
        assertThrows(CurrencyMismatchException.class, () -> {
            transferService.transfer(fromId, toId, amount);
        });

        verify(accountRepository, times(1)).findById(fromId);
        verify(accountRepository, times(1)).findById(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when source account has insufficient balance")
    void shouldThrowInsufficientFundsException() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("300.00");

        Account fromAccount = Account.builder()
                .id(fromId)
                .balance(new BigDecimal("200.00"))
                .currency("USD")
                .build();
        Account toAccount = Account.builder()
                .id(toId)
                .balance(new BigDecimal("50.00"))
                .currency("USD")
                .build();

        when(accountRepository.findById(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(toAccount));

        // WHEN & THEN
        assertThrows(InsufficientFundsException.class, () -> {
            transferService.transfer(fromId, toId, amount);
        });

        verify(accountRepository, times(1)).findById(fromId);
        verify(accountRepository, times(1)).findById(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }


}
