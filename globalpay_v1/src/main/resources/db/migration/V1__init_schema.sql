CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       email VARCHAR(255) UNIQUE NOT NULL,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          user_id UUID NOT NULL,
                          balance DECIMAL(19, 4) NOT NULL DEFAULT 0,
                          currency VARCHAR(3) NOT NULL,
                          version BIGINT NOT NULL DEFAULT 0, -- Тот самый Optimistic Lock
                          CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE transactions (
                              id UUID PRIMARY KEY,
                              from_account_id UUID REFERENCES accounts(id),
                              to_account_id UUID REFERENCES accounts(id),
                              amount_sent DECIMAL(19, 4) NOT NULL,
                              amount_received DECIMAL(19, 4) NOT NULL,
                              exchange_rate DECIMAL(19, 6) NOT NULL,
                              status VARCHAR(20) NOT NULL, -- PENDING, SUCCESS, FAILED
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
