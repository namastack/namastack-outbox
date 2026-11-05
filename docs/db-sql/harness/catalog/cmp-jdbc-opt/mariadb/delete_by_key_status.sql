-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.deleteByKeyAndStatus (query-package, cmp-jdbc-opt)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
