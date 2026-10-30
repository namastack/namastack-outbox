#!/usr/bin/env bash
# H60: one index set (S1|S2) on one scenario. Usage: run-scenario.sh <S1|S2> <A..E> [eq] [extras]
#  steps: run-suite (read benchmarks, container kept) -> equivalence (if eq) -> extras (F1/VM for S1 B/D) ->
#         workload mix (H60 queries, A/B) -> write cost (A) -> remove container
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; E="$DB/experiments"
SET="$1"; S="$2"; EQ="${3:-}"; L="H60-$SET-$S"; C="outbox-db-$L"; Q="$D/queries"
R="$HS/run-explain.sh"
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
docker rm -f -v $C >/dev/null 2>&1
"$HS/run-suite.sh" "$L" "$S" -q "$Q" --ddl "$D/indexes-$SET.sql" --skip-writes --keep > "$DB/results/H60-run-$SET-$S.log" 2>&1 || { echo "suite failed"; exit 1; }
OUT="$DB/results/$L"
if [[ "$EQ" == eq ]]; then
  EQF="$DB/results/H60-eq-$SET-$S.txt"; : > "$EQF"
  EC="$E/G1-iter1/eq-check.sh"
  docker exec -i $C psql -U postgres -X -tA -c "select 'invariant NEW with completed_at: ' || count(*) from outbox_record where status='NEW' and completed_at is not null" >> "$EQF"
  for f in findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter; do
    for p in 64 256; do
      "$EC" $C "$HS/queries/$f.sql" "$Q/$f.sql" parts=$p >> "$EQF" 2>&1 || true
      [[ $f == findRecordKeys* ]] && "$EC" $C "$HS/queries/$f.sql" "$Q/$f.sql" --head 10 parts=$p >> "$EQF" 2>&1 || true
    done
    "$EC" $C "$HS/queries/$f.sql" "$Q/$f.sql" generic=1 parts=64 >> "$EQF" 2>&1 || true
  done
  cmpf() { echo "### $*" >> "$EQF"; "$HS/compare-results.sh" $C "$@" 2>&1 | tail -3 >> "$EQF"; }
  for k in 10 100 1000; do for g in "" --generic; do cmpf "$HS/queries/findIncompleteRecordPages.sql" "$Q/findIncompleteRecordPages.sql" --var keys=$k $g; done; done
  for p in 64 256; do cmpf "$HS/queries/findOldestCreatedAtByPartitions.sql" "$Q/findOldestCreatedAtByPartitions.sql" --var parts=$p; done
  for cp in 0.1 0.9; do for lim in 1 1000; do
    cmpf "$E/H41/eq/orig_findCompletedRecordsBefore_maxrows.sql" "$Q/findCompletedRecordsBefore.sql" --var cutpct=$cp --var limit=$lim
    cmpf "$E/H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql" "$Q/deleteCompletedRecords_selectIds.sql" --var cutpct=$cp --var limit=$lim
  done; done
  for lim in 1 1000; do
    cmpf "$E/H41/eq/orig_findFailedRecordsBefore_maxrows.sql" "$Q/findFailedRecordsBefore.sql" --var limit=$lim
    cmpf "$E/H41/eq/orig_deleteFailedRecords_selectIds_maxrows.sql" "$Q/deleteFailedRecords_selectIds.sql" --var limit=$lim
  done
fi
if [[ "$SET" == S1 && ( "$S" == B || "$S" == D ) ]]; then
  # F1: same filter-off query with and without extended statistics (in place)
  FQ="$Q/findRecordKeysInPartitions_nofilter.sql"
  docker exec -i $C psql -U postgres -X -q -c "DROP STATISTICS outbox_record_status_completed_stx" -c "ANALYZE outbox_record"
  "$R" -c $C -s $L-nostats -q "$Q" --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_nofilter
  "$R" -c $C -s $L-nostats -q "$Q" --generic --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_nofilter
  "$R" -c $C -s $L-nostats -q "$Q" --costs -r 1 findRecordKeysInPartitions_nofilter
  docker exec -i $C psql -U postgres -X -q -c "CREATE STATISTICS outbox_record_status_completed_stx (mcv) ON status, completed_at FROM outbox_record" -c "ANALYZE outbox_record"
  # baseline (unrewritten) filter-off query on the S1 index set, to see whether the 204 ms flip is gone for the old text too
  "$R" -c $C -s $L-origtext -q "$HS/queries" --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_nofilter
fi
if [[ "$S" == A || "$S" == B ]]; then
  "$HS/workload-mix.sh" $C -f "$D/workload-mix.sql" -o "$OUT/index-usage.txt"
  if [[ "$SET" == S1 && "$S" == B ]]; then "$HS/workload-mix.sh" $C -f "$D/workload-mix.sql" --var filter=false -o "$OUT/index-usage-nofilter.txt"; fi
fi
if [[ "$S" == A ]]; then "$HS/write-cost.sh" $C -o "$OUT/write-cost.txt"; fi
echo "done $L"
