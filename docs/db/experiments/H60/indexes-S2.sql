-- H60 S2: S1 + U2b (record_key, completed_at, created_at, id) replacing record_key_completed_created.
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

-- H05 variant X (diagnostic): multivariate MCV statistics so the planner knows status = 'NEW' implies
-- completed_at IS NULL (independence assumption gives 2% x 2% = 0.04% -> 343 rows in D, actual 19,000).
-- Apply on top of the H04/H05 indexes, run the UNCHANGED H04 query files.
CREATE STATISTICS IF NOT EXISTS outbox_record_status_completed_stx (mcv)
    ON status, completed_at FROM outbox_record;
ANALYZE outbox_record;

-- H16 U2b on top of H08a: record_key_completed_created_id replaces record_key_completed_created
CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_completed_created_id
    ON outbox_record (record_key, completed_at, created_at, id);
DROP INDEX IF EXISTS idx_outbox_record_record_key_completed_created;
