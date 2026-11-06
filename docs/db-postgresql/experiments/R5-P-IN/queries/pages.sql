-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: user idea 1a (round 5): IN form, literal completed_at IS NULL, top-level ORDER BY
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
