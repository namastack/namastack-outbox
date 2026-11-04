-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: round 3 PF-NS: IN form, no ORDER BY, client sort
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL
