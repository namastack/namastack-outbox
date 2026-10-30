#!/usr/bin/env bash
# H60 removal tests on S1: drop one index at a time (in place), measure the queries that could use it; then write cost per drop on A.
# Usage: run-drops.sh <A|B> | wc
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/queries"; R="$HS/run-explain.sh"
if [[ "$1" == wc ]]; then
  for drop in psr sr st; do
    C=outbox-db-H60-wc-$drop; docker rm -f -v $C >/dev/null 2>&1
    case $drop in psr) IX=idx_outbox_record_partition_status_retry;; sr) IX=idx_outbox_record_status_retry;; st) IX=idx_outbox_record_status;; esac
    f=$(mktemp --suffix=.sql); { cat "$D/indexes-S1.sql"; echo "DROP INDEX IF EXISTS $IX;"; } > $f
    "$HS/start-postgres.sh" H60-wc-$drop A --ddl $f >/dev/null || exit 1
    "$HS/write-cost.sh" $C -o "$DB/results/H60-drop-wc-$drop.txt"
    docker rm -f -v $C >/dev/null; rm -f $f
  done; exit 0
fi
S="$1"; L="H60-drop-$S"; C=outbox-db-$L; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT; docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" $L $S --ddl "$D/indexes-S1.sql" >/dev/null || exit 1
P() { docker exec -i $C psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
SUB="countByStatus countRecordsByPartition countRecordsByPartitions findOldestCreatedAtByPartitions findOldestDueNextRetryAtByPartitions_filter findOldestDueNextRetryAtByPartitions_nofilter findFailedRecords findFailedRecordsBefore deleteFailedRecords_selectIds deleteByStatus findPendingRecords findIncompleteRecordPages findIncompleteRecordsByRecordKey"
KEYS="findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter"
meas() { # tag
  "$R" -c $C -s $L -q "$Q" --tag $1 $SUB
  "$R" -c $C -s $L -q "$Q" --generic --tag $1 findOldestDueNextRetryAtByPartitions_filter findOldestDueNextRetryAtByPartitions_nofilter countRecordsByPartitions findOldestCreatedAtByPartitions
  "$R" -c $C -s $L -q "$Q" --set max_parallel_workers=0 --tag serial-$1 $KEYS
}
declare -A DDL=( [psr]="CREATE INDEX idx_outbox_record_partition_status_retry ON outbox_record (partition_no, status, next_retry_at)" [sr]="CREATE INDEX idx_outbox_record_status_retry ON outbox_record (status, next_retry_at)" [st]="CREATE INDEX idx_outbox_record_status ON outbox_record (status)" [rkcc]="CREATE INDEX idx_outbox_record_record_key_completed_created ON outbox_record (record_key, completed_at, created_at)" )
declare -A NAME=( [psr]=idx_outbox_record_partition_status_retry [sr]=idx_outbox_record_status_retry [st]=idx_outbox_record_status [rkcc]=idx_outbox_record_record_key_completed_created )
P -tA -c "select indexdef from pg_indexes where tablename='outbox_record'" > "$DB/results/$L/indexdefs.txt"
meas all
for k in psr sr st; do
  P -c "DROP INDEX ${NAME[$k]}" -c "ANALYZE outbox_record"
  meas drop$k
  P -c "${DDL[$k]}" -c "VACUUM ANALYZE outbox_record"
done
