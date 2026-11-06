-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-7 (from QP-count_partitions-3)
SELECT COUNT(*) FROM (VALUES :partitionsRows) AS p(n) JOIN outbox_record r ON r.partition_no = p.n AND r.status = :status AND r.completed_at IS NULL
