-- H02: partial index over incomplete records (NEW + FAILED), keyed for the predecessor probe.
-- Applied with run-suite.sh / start-postgres.sh --ddl (before seeding) or --post-seed-ddl.
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
