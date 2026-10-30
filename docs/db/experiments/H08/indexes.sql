-- H08a: proposed G1 index set for Postgres (= H06 + drop of the unused record_key_created).
--   add  idx_outbox_record_incomplete_created (partial, covering)   -- outer early-stop scan (H04)
--   keep idx_outbox_record_record_key_completed_created              -- head-of-key probes (H06), G2 bulk load
--   keep idx_outbox_record_status_retry                              -- MIN on/off, findFailedRecords(cutoff)
--   drop idx_outbox_record_record_key_created                        -- prefix-duplicate of record_key_completed_created
CREATE INDEX IF NOT EXISTS idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE completed_at IS NULL;
DROP INDEX IF EXISTS idx_outbox_record_incomplete_key;
DROP INDEX IF EXISTS idx_outbox_record_record_key_created;
