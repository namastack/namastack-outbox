-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: round 3 screen extension X0: = ANY(array), no ORDER BY, client sort
SELECT * FROM outbox_record WHERE record_key = ANY(CAST(ARRAY[:recordKeys] AS varchar[])) AND status = :status AND completed_at IS NULL
