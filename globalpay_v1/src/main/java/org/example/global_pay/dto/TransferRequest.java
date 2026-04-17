package org.example.global_pay.dto;


import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {
    @NotNull(message = "From account ID is required")
    private UUID fromId;

    @NotNull(message = "To account ID is required")
    private UUID toId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    @DecimalMax(value = "100000.00", message = "Transfer amount cannot exceed 100,000")
    @Digits(integer = 12, fraction = 2, message = "Amount must have maximum 2 decimal places")
    private BigDecimal amount;

    @NotNull(message = "Idempotency key is required")
    private UUID idempotencyKey;
}
