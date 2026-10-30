# Index states applied in place on one container (sourced by the drivers). Each ends with VACUUM ANALYZE.
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
state_H02() { PSQLC < "$DB/experiments/H02/indexes.sql"; PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
state_H03() { PSQLC < "$DB/experiments/H03/indexes.sql"; PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
state_H04() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_partition"; PSQLC < "$DB/experiments/H04/indexes.sql"; PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
state_H03b() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_created" -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_partition"; PSQLC < "$DB/experiments/H03/indexes-nonpartial.sql"; PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
