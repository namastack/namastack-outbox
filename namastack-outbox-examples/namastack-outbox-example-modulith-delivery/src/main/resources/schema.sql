CREATE TABLE IF NOT EXISTS customer_order (
    id VARCHAR(36) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    amount_cents BIGINT NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS invoice (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    amount_cents BIGINT NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    amount_cents BIGINT NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS notification (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    version BIGINT NOT NULL
);

-- Keep local demo databases from the earlier SQL-based example usable.
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE notification ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
