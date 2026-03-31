package org.example.global_pay.repository;

import org.example.global_pay.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    long countByIdempotencyKey(UUID idempotencyKey);
    Page<Transaction> findAllByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(
            UUID fromId, UUID toId, Pageable pageable
    );
    Optional<Transaction> findByIdempotencyKey(UUID idempotencyKey);
}
