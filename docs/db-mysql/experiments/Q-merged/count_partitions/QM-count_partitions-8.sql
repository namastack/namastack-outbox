-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-8 (from QP-count_partitions-4)
SELECT /*+ SKIP_SCAN(outbox_record) */ COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
