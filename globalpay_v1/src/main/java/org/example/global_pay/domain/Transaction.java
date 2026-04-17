package org.example.global_pay.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "from_account_id")
    private UUID fromAccountId;

    @Column(name = "to_account_id")
    private UUID toAccountId;

    @Column(name = "amount_sent")
    private BigDecimal amountSent;

    @Column(name = "amount_received")
    private BigDecimal amountReceived;

    @Column(name = "exchange_rate")
    private BigDecimal exchangeRate;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    @Column(name = "created_at")
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private UUID idempotencyKey;

    @Column(name = "failure_reason")
    private String failureReason;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

}
