-- Добавляем колонку. UUID в Postgres — это встроенный тип.
ALTER TABLE transactions
    ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX idx_transactions_idempotency_key
    ON transactions(idempotency_key);
