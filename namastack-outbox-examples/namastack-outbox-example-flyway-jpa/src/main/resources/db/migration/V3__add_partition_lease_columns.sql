ALTER TABLE outbox_partition
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE outbox_partition
    ADD COLUMN draining BOOLEAN NOT NULL DEFAULT FALSE;
