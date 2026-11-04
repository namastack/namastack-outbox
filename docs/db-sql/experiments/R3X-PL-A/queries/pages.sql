-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: round 3 U1: IN form without ORDER BY (client sort), completed_at IS NULL
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL
