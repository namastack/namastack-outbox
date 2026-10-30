-- method: countByStatus(status)
-- sql: countByStatusQuery
-- class: periodic (metrics: OutboxMetricsCollector and OutboxRecordMetricsMeterBinder, every status per scrape)
-- variants: status=NEW status=FAILED status=COMPLETED
\ir _params.sql
\if :generic
PREPARE q(varchar) AS SELECT COUNT(*) FROM outbox_record WHERE status = $1;
:explain EXECUTE q(:'status');
DEALLOCATE q;
\else
:explain SELECT COUNT(*) FROM outbox_record
WHERE status = :'status';
\endif
