# Index states applied in place (sourced by the drivers; needs $C and $DB). Each ends with VACUUM ANALYZE.
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
va() { PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
state_H02() { PSQLC < "$DB/experiments/H02/indexes.sql"; va; }
state_H04() { PSQLC < "$DB/experiments/H04/indexes.sql"; va; }
state_H12alt() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_key" -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_created"; PSQLC < "$DB/experiments/H12/indexes-status-new.sql"; va; }
state_H10add() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_new_key_created" -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_key" -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_created"; PSQLC < "$DB/experiments/H10/indexes.sql"; va; }
state_H10() { PSQLC < "$DB/experiments/H10/indexes-replace.sql"; va; }
state_H10noid() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_record_key_status_created"; PSQLC < "$DB/experiments/H10/indexes-noid.sql"; va; }
