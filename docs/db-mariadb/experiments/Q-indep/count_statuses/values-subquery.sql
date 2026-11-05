-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByStatuses (query-package, mdb-opt)
-- note: form values-subquery; needs code change
WITH s(status) AS (VALUES :statusesRows) SELECT s.status, (SELECT COUNT(*) FROM outbox_record r WHERE r.status = s.status) FROM s
