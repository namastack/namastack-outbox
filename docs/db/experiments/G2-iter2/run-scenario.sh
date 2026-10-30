#!/usr/bin/env bash
# G2 iteration 2, one scenario, one container (outbox-db-H14), states applied in place post-seed:
#   ctl(baseline idx) -> H08a -> U2b -> (back to H08a idx) + H10.  Variants (qdirs): ctl L Ln Lc Lcn P1 P2 LP.
# Usage: run-scenario.sh <S>      results: results/G2i2-<S>/ (tag = <state>-<variant>), results/G2i2-eq-<S>.txt
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; C=outbox-db-H14; RE="$HS/run-explain.sh"; L="G2i2-$S"; REF="$D/ref/$S"; rm -rf "$REF" "$DB/results/$L"
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
va() { PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" H14 "$S" >/dev/null || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
EQ="$DB/results/G2i2-eq-$S.txt"; : > "$EQ"
eqs() { # tag variant
  for k in 10 100 1000; do for g in 0 1; do "$D/eq2.sh" $C "$REF" "$1" "$Q/$2/findIncompleteRecordPages.sql" keys=$k generic=$g >> "$EQ"; done; done; }
eqkey() { for g in 0 1; do "$D/eq2.sh" $C "$REF" "$1" "$Q/ctl/findIncompleteRecordsByRecordKey.sql" generic=$g >> "$EQ"; done; }
measure() { # state variant
  local t="$1-$2"
  "$RE" -c $C -s $L -q "$Q/$2" --tag $t findIncompleteRecordPages
  "$RE" -c $C -s $L -q "$Q/$2" --generic --tag $t findIncompleteRecordPages
  if [[ $S == B || $S == D ]]; then "$RE" -c $C -s $L -q "$Q/$2" --costs -r 1 --tag $t findIncompleteRecordPages; fi
}
g1keys() { # state
  for qd in "$D/../G1-iter2/qdirs/H08"; do
    "$RE" -c $C -s $L-g1 -q "$qd" --set max_parallel_workers=0 --tag serial-$1 findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter
    "$RE" -c $C -s $L-g1 -q "$qd" --generic --set max_parallel_workers=0 --tag serial-$1 findRecordKeysInPartitions_filter findOldestDueNextRetryAtByPartitions_filter
  done
}
sizes() { PSQLC -tA -F' ' -c "SELECT indexrelname, pg_relation_size(indexrelid) FROM pg_stat_user_indexes WHERE relname='outbox_record' ORDER BY 1" > "$DB/results/$L/sizes-$1.txt"; }
VARS_ALL="ctl L Ln Lc Lcn Lo Lon P1 P2 LP"
mkdir -p "$DB/results/$L"
echo "## $S baseline idx" >> "$EQ"
for k in 10 100 1000; do for g in 0 1; do "$D/eq2.sh" $C "$REF" ref "$Q/ctl/findIncompleteRecordPages.sql" keys=$k generic=$g >> "$EQ"; done; done
for g in 0 1; do "$D/eq2.sh" $C "$REF" ref "$Q/ctl/findIncompleteRecordsByRecordKey.sql" generic=$g >> "$EQ"; done
PSQLC -tA -c "select 'invariant NEW with completed_at: ' || count(*) from outbox_record where status='NEW' and completed_at is not null" >> "$EQ"
echo "## H08a" >> "$EQ"
PSQLC < "$DB/experiments/H08/indexes.sql"; va; sizes h08a
"$RE" -c $C -s $L -q "$Q/ctl" --tag h08a-ctl findIncompleteRecordsByRecordKey
"$RE" -c $C -s $L -q "$Q/ctl" --generic --tag h08a-ctl findIncompleteRecordsByRecordKey
eqkey h08a-ctl
for v in $VARS_ALL; do measure h08a $v; eqs h08a-$v $v; done
g1keys h08a
echo "## U2b" >> "$EQ"
PSQLC -c "CREATE INDEX idx_outbox_record_record_key_completed_created_id ON outbox_record (record_key, completed_at, created_at, id)" -c "DROP INDEX idx_outbox_record_record_key_completed_created"; va; sizes u2b
"$RE" -c $C -s $L -q "$Q/ctl" --tag u2b-ctl findIncompleteRecordsByRecordKey
eqkey u2b-ctl
for v in ctl L Ln Lc Lcn Lo Lon; do measure u2b $v; eqs u2b-$v $v; done
g1keys u2b
echo "## H10 (on H08a)" >> "$EQ"
PSQLC -c "CREATE INDEX idx_outbox_record_record_key_completed_created ON outbox_record (record_key, completed_at, created_at)" -c "DROP INDEX idx_outbox_record_record_key_completed_created_id" -c "CREATE INDEX idx_outbox_record_record_key_status_created ON outbox_record (record_key, status, created_at, id)"; va; sizes h10
"$RE" -c $C -s $L -q "$Q/ctl" --tag h10-ctl findIncompleteRecordsByRecordKey
eqkey h10-ctl
for v in ctl L Ln Lc Lon; do measure h10 $v; eqs h10-$v $v; done
echo "PASS: $(grep -c '^PASS' "$EQ")  FAIL: $(grep -c '^FAIL' "$EQ")"
echo "scenario $S done"
