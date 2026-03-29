package org.example.global_pay.service;

import org.example.global_pay.domain.Account;
import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.*;
import org.example.global_pay.repository.AccountRepository;
import org.example.global_pay.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
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

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUpRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }


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

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .idempotencyKey(UUID.randomUUID())
                .amount(amount)
                .build();

        when(accountRepository.findById(fromId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(toAccount));

        // WHEN
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        transferService.transfer(request);

        assertThat(fromAccount.getBalance()).isEqualByComparingTo("100.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("150.00");


        verify(accountRepository, times(1)).save(fromAccount);
        verify(accountRepository, times(1)).save(toAccount);
        verify(transactionRepository).save(argThat(t ->
                t.getIdempotencyKey().equals(request.getIdempotencyKey()) &&
                        t.getStatus() == TransactionStatus.SUCCESS
        ));
        verify(valueOps).set(anyString(), eq("COMPLETED"), any());
    }

    @Test
    @DisplayName("Should throw SelfTransferException when fromId equals toId")
    void shouldThrowSelfTransferException() {
        // GIVEN
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        TransferRequest request = TransferRequest.builder()
                .fromId(accountId)
                .toId(accountId)
                .amount(amount)
                .build();

        // WHEN & THEN
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertThrows(SelfTransferException.class, () -> {
            transferService.transfer(request);
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

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(amount)
                .build();

        // WHEN & THEN
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertThrows(AccountNotFoundException.class, () -> {
            transferService.transfer(request);
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

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(amount)
                .build();

        // WHEN & THEN
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertThrows(AccountNotFoundException.class, () -> {
            transferService.transfer(request);
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

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(amount)
                .build();

        // WHEN & THEN
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertThrows(CurrencyMismatchException.class, () -> {
            transferService.transfer(request);
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

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(amount)
                .build();

        // WHEN & THEN
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertThrows(InsufficientFundsException.class, () -> {
            transferService.transfer(request);
        });

        verify(accountRepository, times(1)).findById(fromId);
        verify(accountRepository, times(1)).findById(toId);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Should prevent duplicate transfers with same idempotency key")
    void shouldPreventDuplicateTransfers() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .idempotencyKey(UUID.randomUUID())
                .amount(amount)
                .build();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        // WHEN & THEN
        assertThrows(DuplicateRequestException.class, () -> {
            transferService.transfer(request);
        });
        verify(valueOps).setIfAbsent(anyString(), anyString(), any());
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
    }

}
