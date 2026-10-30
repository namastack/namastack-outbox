-- method: countByStatus(status)
-- sql: countByStatusQuery (unchanged; H30 measures it under different index sets and visibility-map states)
-- class: periodic (metrics: OutboxMetricsCollector every 15 s; OutboxRecordMetricsMeterBinder per scrape)
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
