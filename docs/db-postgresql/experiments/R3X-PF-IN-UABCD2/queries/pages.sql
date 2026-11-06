-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: round 3 screen extension X0: IN + ORDER BY record_key, created_at, id (B1)
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
