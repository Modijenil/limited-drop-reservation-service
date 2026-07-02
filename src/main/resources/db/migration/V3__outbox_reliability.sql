ALTER TABLE outbox_events
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME(6) NULL,
    ADD COLUMN last_error VARCHAR(1000) NULL;

UPDATE outbox_events SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;

CREATE INDEX idx_outbox_status_next_attempt ON outbox_events (status, next_attempt_at);
