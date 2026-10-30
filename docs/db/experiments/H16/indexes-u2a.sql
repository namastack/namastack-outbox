-- U2a (portable, zero DDL): existing (record_key, completed_at, created_at) serves both with `completed_at IS NULL`
-- (per-key incremental sort on id, tiny). Only the unused record_key_created is dropped.
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
