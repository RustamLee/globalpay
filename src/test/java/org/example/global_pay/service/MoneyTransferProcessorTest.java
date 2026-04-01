package org.example.global_pay.service;

import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;


import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MoneyTransferProcessorTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private MoneyTransferProcessor processor;

    @Test
    @DisplayName("Should execute transfer successfully and update balances and transaction status")
    void shouldExecuteTransferSuccessfully() {

        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("20.00");

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

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId).toId(toId).amount(amount).build();

        Transaction transaction = Transaction.builder()
                .id(UUID.randomUUID()).status(TransactionStatus.PENDING).build();

        when(accountRepository.findByIdForUpdate(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByIdForUpdate(toId)).thenReturn(Optional.of(toAccount));

        // WHEN
        processor.execute(request, transaction);

        // THEN
        assertThat(fromAccount.getBalance()).isEqualByComparingTo("180.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("70.00");

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(accountRepository).save(fromAccount);
        verify(accountRepository).save(toAccount);
        verify(transactionRepository).save(transaction);
    }

    @Test
    @DisplayName("Should throw MismatchedCurrencyException when accounts have different currencies")
    void shouldThrowMismatchedCurrencyException() {

        // GIVEN
        Account from = Account.builder().id(UUID.randomUUID()).balance(new BigDecimal("100")).currency("USD").build();
        Account to = Account.builder().id(UUID.randomUUID()).balance(new BigDecimal("100")).currency("EUR").build();

        when(accountRepository.findByIdForUpdate(any())).thenReturn(Optional.of(from)).thenReturn(Optional.of(to));

        TransferRequest request = TransferRequest.builder().fromId(from.getId()).toId(to.getId()).amount(new BigDecimal("10")).build();
        Transaction tx = new Transaction();

        // WHEN & THEN
        assertThrows(CurrencyMismatchException.class, () -> processor.execute(request, tx));
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        assertThat(tx.getStatus()).isNotEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException when source account is missing")
    void shouldThrowAccountNotFoundExceptionForSource(){
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        when(accountRepository.findByIdForUpdate(fromId)).thenReturn(Optional.empty());

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("10.00"))
                .build();

        Transaction tx = new Transaction(); // Просто пустой объект для параметра

        // WHEN & THEN
        assertThrows(AccountNotFoundException.class, () -> processor.execute(request, tx));

        verify(accountRepository).findByIdForUpdate(fromId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException when destination account is missing")
    void shouldThrowAccountNotFoundExceptionForDestination(){
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        Account fromAccount = Account.builder()
                .id(fromId)
                .balance(new BigDecimal("200.00"))
                .currency("USD")
                .build();

        when(accountRepository.findByIdForUpdate(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByIdForUpdate(toId)).thenReturn(Optional.empty());

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(amount)
                .build();

        Transaction tx = new Transaction();

        // WHEN & THEN
        assertThrows(AccountNotFoundException.class, () -> processor.execute(request, tx));

        verify(accountRepository).findByIdForUpdate(fromId);
        verify(accountRepository).findByIdForUpdate(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw SelfTransferException when fromId equals toId")
    void shouldThrowSelfTransferException() {
        // GIVEN
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.00");

        TransferRequest request = TransferRequest.builder()
                .fromId(accountId)
                .toId(accountId)
                .amount(amount)
                .build();

        Transaction tx = new Transaction(); // Просто пустой объект для параметра

        // WHEN & THEN
        assertThrows(SelfTransferException.class, () -> processor.execute(request, tx));

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when source account has insufficient balance")
    void shouldThrowInsufficientFundsException(){
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

        when(accountRepository.findByIdForUpdate(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByIdForUpdate(toId)).thenReturn(Optional.of(toAccount));

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(amount)
                .build();

        Transaction tx = new Transaction();

        // WHEN & THEN
        assertThrows(InsufficientFundsException.class, () -> processor.execute(request, tx));

        verify(accountRepository).findByIdForUpdate(fromId);
        verify(accountRepository).findByIdForUpdate(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }


}
