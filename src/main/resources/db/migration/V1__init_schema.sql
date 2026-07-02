CREATE TABLE drops (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL,
    opens_at DATETIME(6) NOT NULL,
    closes_at DATETIME(6) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_units INT NOT NULL,
    available_units INT NOT NULL,
    held_units INT NOT NULL,
    confirmed_units INT NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_drop_units_non_negative CHECK (total_units >= 0 AND available_units >= 0 AND held_units >= 0 AND confirmed_units >= 0)
);

CREATE INDEX idx_drops_status_opens_at ON drops (status, opens_at);

CREATE TABLE holds (
    id CHAR(36) PRIMARY KEY,
    drop_id BIGINT NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_hold_drop FOREIGN KEY (drop_id) REFERENCES drops(id),
    CONSTRAINT chk_hold_quantity_positive CHECK (quantity > 0),
    CONSTRAINT uq_hold_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_holds_status_expires_at ON holds (status, expires_at);
CREATE INDEX idx_holds_drop_id ON holds (drop_id);
CREATE INDEX idx_holds_user_id ON holds (user_id);

CREATE TABLE reservations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hold_id CHAR(36) NOT NULL,
    drop_id BIGINT NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    total_amount DECIMAL(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_reservation_hold FOREIGN KEY (hold_id) REFERENCES holds(id),
    CONSTRAINT fk_reservation_drop FOREIGN KEY (drop_id) REFERENCES drops(id),
    CONSTRAINT uq_reservation_hold UNIQUE (hold_id),
    CONSTRAINT chk_reservation_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_reservations_drop_id ON reservations (drop_id);

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    endpoint VARCHAR(100) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_body TEXT NULL,
    http_status INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL
);

CREATE TABLE outbox_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL
);

CREATE INDEX idx_outbox_status_created_at ON outbox_events (status, created_at);
