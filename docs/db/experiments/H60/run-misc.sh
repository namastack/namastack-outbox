#!/usr/bin/env bash
# H60 edge equivalence (key_ties 0/1, S1) and visibility-map test (S1, B).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; E="$DB/experiments"; Q="$D/queries"
case "$1" in
edge)
  OUT="$DB/results/H60-eq-edge.txt"; : > "$OUT"; C=outbox-db-H60-edge; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
  for ties in 0 1; do
    docker rm -f -v $C >/dev/null 2>&1
    "$HS/start-postgres.sh" H60-edge none --ddl "$D/indexes-S1.sql" >/dev/null
    docker exec -i $C psql -U postgres -X -q -v key_ties=$ties < "$HS/seed-edge.sql"; docker exec $C psql -U postgres -qc "VACUUM ANALYZE"
    echo "## key_ties=$ties" >> "$OUT"
    for g in 0 1; do for f in findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter; do for p in 64 256; do
      "$E/G1-iter1/eq-check.sh" $C "$HS/queries/$f.sql" "$Q/$f.sql" parts=$p generic=$g >> "$OUT" 2>&1 || true
      [[ $f == findRecordKeys* ]] && "$E/G1-iter1/eq-check.sh" $C "$HS/queries/$f.sql" "$Q/$f.sql" --head 3 parts=$p generic=$g >> "$OUT" 2>&1 || true
    done; done; done
    for k in 10 100 1000; do for g in "" --generic; do echo "### pages keys=$k $g" >> "$OUT"; "$HS/compare-results.sh" $C "$HS/queries/findIncompleteRecordPages.sql" "$Q/findIncompleteRecordPages.sql" --var keys=$k $g 2>&1 | tail -2 >> "$OUT"; done; done
    for p in 64 256; do echo "### oldest_created parts=$p" >> "$OUT"; "$HS/compare-results.sh" $C "$HS/queries/findOldestCreatedAtByPartitions.sql" "$Q/findOldestCreatedAtByPartitions.sql" --var parts=$p 2>&1 | tail -2 >> "$OUT"; done
    for lim in 1 1000; do for f in findCompletedRecordsBefore deleteCompletedRecords_selectIds; do echo "### $f lim=$lim" >> "$OUT"; "$HS/compare-results.sh" $C "$E/H41/eq/orig_${f}_maxrows.sql" "$Q/$f.sql" --var limit=$lim 2>&1 | tail -2 >> "$OUT"; done
      for f in findFailedRecordsBefore deleteFailedRecords_selectIds; do echo "### $f lim=$lim" >> "$OUT"; "$HS/compare-results.sh" $C "$E/H41/eq/orig_${f}_maxrows.sql" "$Q/$f.sql" --var limit=$lim 2>&1 | tail -2 >> "$OUT"; done; done
  done
  echo "PASS: $(grep -c '^PASS' "$OUT") FAIL: $(grep -c '^FAIL' "$OUT")" ;;
vm)
  L=H60vm-B; N=H60-vm; C=outbox-db-$N; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
  docker rm -f -v $C >/dev/null 2>&1
  "$HS/start-postgres.sh" $N B --ddl "$D/indexes-S1.sql" >/dev/null || exit 1
  P() { docker exec -i $C psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
  P -c "ALTER TABLE outbox_record SET (autovacuum_enabled = false)"
  T="findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter findOldestCreatedAtByPartitions findIncompleteRecordPages"
  q() { "$HS/run-explain.sh" -c $C -s $L -q "$Q" --set max_parallel_workers=0 --tag "serial-$1" $T; }
  q fresh; prev=0
  for n in 5000 20000 50000; do
    P -c "UPDATE outbox_record SET status='COMPLETED', completed_at=clock_timestamp() WHERE id IN (SELECT id FROM outbox_record WHERE status='NEW' ORDER BY created_at, id LIMIT $((n-prev)))"; prev=$n
    P -tA -c "SELECT 'after $n updates: relallvisible/relpages=' || relallvisible || '/' || relpages FROM pg_class WHERE relname='outbox_record'" | tee -a "$DB/results/$L/vm.txt"
    q upd$n
  done
  P -c "VACUUM outbox_record"; q vacuumed ;;
esac
