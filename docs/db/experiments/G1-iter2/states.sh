# Index states for G1 iteration 2, applied in place (sourced). Each ends with VACUUM ANALYZE + CHECKPOINT.
# Sequence: H04 (post-seed) -> H05 (same) -> H05X stats on/off -> H06 -> H08a -> H07a -> H07b -> H08b
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
vac() { PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
state_H04()  { PSQLC < "$DB/experiments/H04/indexes.sql"; vac; }
state_H05X() { PSQLC < "$DB/experiments/H05/stats.sql"; }
state_H05X_off() { PSQLC -c "DROP STATISTICS IF EXISTS outbox_record_status_completed_stx" -c "ANALYZE outbox_record"; }
state_H06()  { PSQLC < "$DB/experiments/H06/indexes.sql"; vac; }
state_H08()  { PSQLC < "$DB/experiments/H08/indexes.sql"; vac; }
state_H07a() { PSQLC < "$DB/experiments/H07/indexes.sql"; vac; }
state_H07b() { PSQLC < "$DB/experiments/H07/indexes-covering.sql"; vac; }
state_H08b() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_completed_created"; PSQLC < "$DB/experiments/H08/indexes-b.sql"; vac; }
# --- added by the iteration-2 experimenter: explicit resets so states can be applied in any order
reset_g1() { PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_key" -c "DROP INDEX IF EXISTS idx_outbox_record_incomplete_created" -c "DROP INDEX IF EXISTS idx_outbox_record_completed_created"; }
state_H04()  { reset_g1; PSQLC < "$DB/experiments/H04/indexes.sql"; vac; }
state_H06()  { reset_g1; PSQLC < "$DB/experiments/H06/indexes.sql"; vac; }
state_H08()  { reset_g1; PSQLC < "$DB/experiments/H08/indexes.sql"; vac; }   # also drops record_key_created (stays dropped)
state_H07a() { reset_g1; PSQLC < "$DB/experiments/H07/indexes.sql"; vac; }
state_H07b() { reset_g1; PSQLC < "$DB/experiments/H07/indexes-covering.sql"; vac; }
state_H09a() { reset_g1; PSQLC < "$DB/experiments/H09/indexes-a.sql"; vac; }
state_H09b() { reset_g1; PSQLC < "$DB/experiments/H09/indexes-b.sql"; vac; }
restore_key_created() { PSQLC -c "CREATE INDEX IF NOT EXISTS idx_outbox_record_record_key_created ON outbox_record (record_key, created_at)"; }
state_H09n_base() { reset_g1; restore_key_created; PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_record_key_status_created"; vac; }
state_H09n_h10() { reset_g1; PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_record_key_status_created"; PSQLC < "$DB/experiments/H09/indexes-n-h10.sql"; vac; }
state_H09n_outer() { reset_g1; PSQLC -c "DROP INDEX IF EXISTS idx_outbox_record_record_key_status_created" -c "DROP INDEX IF EXISTS idx_outbox_record_status_created"; PSQLC < "$DB/experiments/H09/indexes-n-h10.sql"; PSQLC < "$DB/experiments/H09/indexes-n-outer.sql"; vac; }
