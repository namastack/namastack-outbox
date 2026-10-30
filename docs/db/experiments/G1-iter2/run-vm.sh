#!/usr/bin/env bash
# Visibility-map sensitivity: H08a index set, autovacuum off, bursts of "processing" updates (oldest NEW -> COMPLETED)
# without VACUUM; key queries (custom-serial) before / after each burst. Usage: run-vm.sh <S> <container-suffix>
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; N="$2"; C=outbox-db-$N; L=H08vm-$S; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"
docker rm -f -v $C >/dev/null 2>&1; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
"$HS/start-postgres.sh" $N $S --ddl "$DB/experiments/H08/indexes.sql" >/dev/null || exit 1
P() { docker exec -i $C psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
P -c "ALTER TABLE outbox_record SET (autovacuum_enabled = false)"
T="findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter_fence findOldestDueNextRetryAtByPartitions_filter"
q() { "$HS/run-explain.sh" -c $C -s $L -q "$Q/H08" --set max_parallel_workers=0 --tag "serial-$1" $T; }
q fresh
prev=0
for n in 5000 20000 50000; do
  P -c "UPDATE outbox_record SET status='COMPLETED', completed_at=clock_timestamp() WHERE id IN (SELECT id FROM outbox_record WHERE status='NEW' ORDER BY created_at, id LIMIT $((n-prev)))"; prev=$n
  P -tA -c "SELECT 'after $n updates: relallvisible/relpages=' || relallvisible || '/' || relpages FROM pg_class WHERE relname='outbox_record'" | tee -a "$DB/results/$L/vm.txt"
  q upd$n
done
P -c "VACUUM outbox_record"; q vacuumed
