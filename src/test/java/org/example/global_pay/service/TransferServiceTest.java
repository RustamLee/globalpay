package org.example.global_pay.service;

import org.example.global_pay.domain.Transaction;
import org.example.global_pay.domain.TransactionStatus;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.DuplicateRequestException;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {
    @Mock
    private AccountRepository accountRepository;

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
    @DisplayName("Should successfully coordinate money transfer")
    void shouldTransferMoneyBetweenAccounts() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        Transaction pendingTx = Transaction.builder()
                .id(UUID.randomUUID())
                .status(TransactionStatus.PENDING)
                .idempotencyKey(request.getIdempotencyKey())
                .build();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(moneyTransferProcessor.createPendingTransaction(any(TransferRequest.class))).thenReturn(pendingTx);

        // WHEN
        transferService.transfer(request);

        // THEN
        verify(moneyTransferProcessor).createPendingTransaction(request);
        verify(moneyTransferProcessor).execute(request, pendingTx);

        // 3. Проверяем финализацию в Redis
        verify(valueOps).set(anyString(), eq("COMPLETED"), any());

    }


    @Test
    @DisplayName("Should prevent duplicate transfers with same idempotency key")
    void shouldPreventDuplicateTransfers() {
        // GIVEN
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        TransferRequest request = TransferRequest.builder()
                .fromId(fromId)
                .toId(toId)
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .build();
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty());

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        // WHEN & THEN
        assertThrows(DuplicateRequestException.class, () -> {
            transferService.transfer(request);
        });
        verify(transactionRepository).findByIdempotencyKey(idempotencyKey);
        verify(valueOps).setIfAbsent(anyString(), anyString(), any());

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(moneyTransferProcessor);
    }

}
