package org.example.global_pay.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.example.global_pay.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Page<Transaction> findAllByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(
            UUID fromId, UUID toId, Pageable pageable
    );


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select t from Transaction t where t.idempotencyKey = :idempotencyKey")
    Optional<Transaction> findByIdempotencyKeyForUpdate(@Param("idempotencyKey") UUID idempotencyKey);

    @Modifying
    @Query(value = """
            INSERT INTO transactions (
                id,
                from_account_id,
                to_account_id,
                amount_sent,
                amount_received,
                exchange_rate,
                status,
                idempotency_key
            )
            VALUES (
                :id,
                :fromAccountId,
                :toAccountId,
                :amountSent,
                :amountReceived,
                :exchangeRate,
                :status,
                :idempotencyKey
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("id") UUID id,
            @Param("fromAccountId") UUID fromAccountId,
            @Param("toAccountId") UUID toAccountId,
            @Param("amountSent") BigDecimal amountSent,
            @Param("amountReceived") BigDecimal amountReceived,
            @Param("exchangeRate") BigDecimal exchangeRate,
            @Param("status") String status,
            @Param("idempotencyKey") UUID idempotencyKey
    );

}
