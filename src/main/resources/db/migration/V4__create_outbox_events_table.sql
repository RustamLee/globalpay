CREATE TABLE outbox_events (
                               id BIGSERIAL PRIMARY KEY,
                               aggregate_id VARCHAR(255) NOT NULL,
                               type VARCHAR(100) NOT NULL,
                               payload TEXT NOT NULL,
                               status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                               attempts INTEGER DEFAULT 0,
                               next_retry_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_status_retry ON outbox_events (status, next_retry_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox_events (aggregate_id);
