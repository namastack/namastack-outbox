#!/usr/bin/env bash
# Equivalence for H32 (inc predicate), H41 (real LIMIT), H42 (single delete) on H08a. Usage: eq.sh <S>  (S = A|B|D|edge)
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; E="$DB/experiments"
S="$1"; C=outbox-db-H3x; OUT="$DB/results/G4G5-eq-$S.txt"; : > "$OUT"
docker rm -f -v $C >/dev/null 2>&1; "$HS/start-postgres.sh" H3x "$S" --ddl "$E/H08/indexes.sql" >/dev/null || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
cmp() { echo "### $*" >> "$OUT"; "$HS/compare-results.sh" $C "$@" 2>&1 | tail -4 >> "$OUT"; }
for st in NEW FAILED; do for p in 64 256; do
  cmp "$HS/queries/countRecordsByPartitions.sql" "$E/H32/queries/countRecordsByPartitions.sql" --var parts=$p --var status=$st
  cmp "$HS/queries/findOldestCreatedAtByPartitions.sql" "$E/H32/queries/findOldestCreatedAtByPartitions.sql" --var parts=$p --var status=$st
done; cmp "$HS/queries/countRecordsByPartition.sql" "$E/H32/queries/countRecordsByPartition.sql" --var status=$st; done
cmp "$HS/queries/findOldestCreatedAtByPartitions.sql" "$E/H32/queries/findOldestCreatedAtByPartitions_lateral.sql" --var parts=64
for cp in 0.1 0.9; do for lim in 1 1000 100000; do
  cmp "$E/H41/eq/orig_findCompletedRecordsBefore_maxrows.sql" "$E/H41/queries/findCompletedRecordsBefore.sql" --var cutpct=$cp --var limit=$lim
  cmp "$E/H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql" "$E/H41/queries/deleteCompletedRecords_selectIds.sql" --var cutpct=$cp --var limit=$lim
  cmp "$E/H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql" "$E/H42/eq/rewr_deleteCompletedRecords_ids.sql" --var cutpct=$cp --var limit=$lim
done; done
for lim in 1 1000; do
  cmp "$E/H41/eq/orig_findFailedRecordsBefore_maxrows.sql" "$E/H41/queries/findFailedRecordsBefore.sql" --var limit=$lim
  cmp "$E/H41/eq/orig_deleteFailedRecords_selectIds_maxrows.sql" "$E/H41/queries/deleteFailedRecords_selectIds.sql" --var limit=$lim
done
# index-only variants: results must not depend on the index set
echo "### index-independence: counts/min with H08a vs after ddl changes" >> "$OUT"
q() { docker exec -i $C psql -U postgres -tAX -c "SELECT (SELECT count(*) FROM outbox_record WHERE status='NEW'),(SELECT count(*) FROM outbox_record WHERE status='FAILED'),(SELECT count(*) FROM outbox_record WHERE status='COMPLETED'),(SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15) AND status='NEW')"; }
q >> "$OUT"
docker exec -i $C psql -U postgres -qX -c "DROP INDEX idx_outbox_record_status" -c "CREATE INDEX idx_outbox_record_status_created ON outbox_record (status, created_at)" -c "ANALYZE outbox_record"
q >> "$OUT"
echo "invariant NEW with completed_at: $(docker exec -i $C psql -U postgres -tAX -c "select count(*) from outbox_record where status='NEW' and completed_at is not null")" >> "$OUT"
echo "PASS: $(grep -c '^PASS' "$OUT")  FAIL: $(grep -c '^FAIL' "$OUT")"
