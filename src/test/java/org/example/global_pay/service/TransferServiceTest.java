package org.example.global_pay.service;

import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.DuplicateRequestException;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransferService transferService;

    @Mock
    private MoneyTransferProcessor moneyTransferProcessor;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUpRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("Should successfully coordinate money transfer with DB lock flow")
    void shouldTransferMoneyBetweenAccounts() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(idempotencyKey)
                .build();

        Transaction pendingTx = Transaction.builder()
                .id(UUID.randomUUID())
                .status(TransactionStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        when(valueOps.get(anyString())).thenReturn(null);
        when(transactionRepository.insertPendingIfAbsent(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(1);
        when(transactionRepository.findByIdempotencyKeyForUpdate(idempotencyKey)).thenReturn(Optional.of(pendingTx));

        transferService.transfer(request);

        verify(transactionRepository).insertPendingIfAbsent(any(), eq(fromId), eq(toId), any(), any(), any(), eq("PENDING"), eq(idempotencyKey));
        verify(moneyTransferProcessor).executeWithLock(request, pendingTx);
        verify(valueOps).set(anyString(), eq("COMPLETED"), any(Duration.class));
    }

    @Test
    @DisplayName("Should reject replay when transaction is already FAILED")
    void shouldRejectFailedReplay() {
        UUID idempotencyKey = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .amount(new BigDecimal("100.00"))
                .build();

        Transaction failedTx = Transaction.builder()
                .id(UUID.randomUUID())
                .status(TransactionStatus.FAILED)
                .idempotencyKey(idempotencyKey)
                .build();

        when(valueOps.get(anyString())).thenReturn(null);
        when(transactionRepository.insertPendingIfAbsent(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(0);
        when(transactionRepository.findByIdempotencyKeyForUpdate(idempotencyKey)).thenReturn(Optional.of(failedTx));

        assertThrows(DuplicateRequestException.class, () -> transferService.transfer(request));

        verifyNoMoreInteractions(moneyTransferProcessor);
    }
}
