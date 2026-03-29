package org.example.global_pay.domain;

import jakarta.persistence.*;
import lombok.*;

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
    private LocalDateTime createdAt;

    @Column(name = "idempotency_key", unique = true)
    private UUID idempotencyKey;
}
