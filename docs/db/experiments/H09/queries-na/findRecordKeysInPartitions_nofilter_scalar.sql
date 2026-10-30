-- H09 non-partial (queries-na): status-based incomplete predicate, no partial index
-- method: findRecordKeysInPartitions(partitions, NEW, batchSize, ignoreRecordKeysWithPreviousFailure = false)
-- sql: recordKeysQueryWithoutPreviousFailureFilterTemplate, H05 rewrite: H04 "first due record of the key" form,
--      but the per-key check is a correlated scalar subquery (o.id = id of the key's first due incomplete record,
--      ORDER BY created_at, id LIMIT 1). A subquery with LIMIT is never flattened into a join, so the planner must
--      probe idx_outbox_record_incomplete_key per outer row (no Materialize + join filter, the H04 D regression).
-- class: hot (every poll when stopOnFirstFailure = false)
-- maxRows: batchSize, ALSO in the SQL as LIMIT :batch (psql var `batch`, default 10).
--          Equivalence check: --var batch=100000000 (full ordered output vs. the original query).
-- Worst case (owned partitions without any due record): -v partlist_override=999 (custom mode only).
-- variants: parts=64 parts=256
\ir _params.sql
\if :{?batch}
\else
\set batch 10
\endif
\if :{?partlist_override}
\if :generic
\else
\set partlist :partlist_override
\endif
\endif
SELECT '$' || (:parts + 3) AS part_n3 \gset
\if :generic
PREPARE q(:part_types, varchar, timestamptz, bigint) AS
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:part_ph)
  AND o.status = :part_n1
  AND o.next_retry_at <= :part_n2
  AND o.id = (
    SELECT e.id FROM outbox_record e
    WHERE e.record_key = o.record_key
      AND e.status IN ('NEW', 'FAILED')
      AND e.status = :part_n1
      AND e.next_retry_at <= :part_n2
    ORDER BY e.created_at ASC, e.id ASC
    LIMIT 1
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :part_n3;
:explain EXECUTE q(:partlist, :'status', :'now_ts', :batch);
DEALLOCATE q;
\else
:explain SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partlist)
  AND o.status = :'status'
  AND o.next_retry_at <= :'now_ts'
  AND o.id = (
    SELECT e.id FROM outbox_record e
    WHERE e.record_key = o.record_key
      AND e.status IN ('NEW', 'FAILED')
      AND e.status = :'status'
      AND e.next_retry_at <= :'now_ts'
    ORDER BY e.created_at ASC, e.id ASC
    LIMIT 1
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :batch;
\endif
