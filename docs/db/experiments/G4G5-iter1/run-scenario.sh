#!/usr/bin/env bash
# G4/G5 iteration 1: one scenario, one container (outbox-db-H3x), index states applied in place on top of H08a.
# Usage: run-scenario.sh <S> [g5]    results: results/G4G5-<S>/ (tag = <state>[-serial])
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; G5="${2:-}"; C=outbox-db-H3x; RE="$HS/run-explain.sh"; L="G4G5-$S"; rm -rf "$DB/results/$L"
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
va() { PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" H3x "$S" >/dev/null || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
mkdir -p "$DB/results/$L"
sizes() { PSQLC -tA -F' ' -c "SELECT indexrelname, pg_relation_size(indexrelid) FROM pg_stat_user_indexes WHERE relname='outbox_record' ORDER BY 1" > "$DB/results/$L/sizes-$1.txt"; }
ddl() { PSQLC -c "$1"; }
P="parts=64 parts=256"
g4() { # state  (original queries)
  local t=$1
  "$RE" -c $C -s $L -q "$Q/orig" --tag $t countByStatus@status=NEW countByStatus@status=FAILED countByStatus@status=COMPLETED countRecordsByPartitions countRecordsByPartition findOldestCreatedAtByPartitions
  "$RE" -c $C -s $L -q "$Q/orig" --generic --tag $t countByStatus@status=COMPLETED countRecordsByPartitions findOldestCreatedAtByPartitions
}
g4vm() { # state: COMPLETED/NEW count after VM cleared
  PSQLC < "$DB/experiments/H30/clear-vm.sql"
  "$RE" -c $C -s $L -q "$Q/orig" --tag $1-vmcleared countByStatus@status=COMPLETED countByStatus@status=NEW
  PSQLC < "$DB/experiments/H30/vacuum.sql"
}
inc() { "$RE" -c $C -s $L -q "$Q/h32" --tag $1 countRecordsByPartitions countRecordsByPartition findOldestCreatedAtByPartitions
        "$RE" -c $C -s $L -q "$Q/h32" --generic --tag $1 countRecordsByPartitions findOldestCreatedAtByPartitions; }
noown() { "$RE" -c $C -s $L -q "$Q/h31" --tag $1 findOldestCreatedAtByPartitions_noowned; }
hot() { # hot-path regression subset (G1 H08 keys, G2 Lon)
  "$RE" -c $C -s $L-hot -q "$D/../G1-iter2/qdirs/H08" --set max_parallel_workers=0 --tag serial-$1 findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter
  "$RE" -c $C -s $L-hot -q "$D/../G2-iter2/qdirs/Lon" --tag $1 findIncompleteRecordPages
}
g5() { # state
  local t=$1
  for cp in 0.1 0.9; do
    "$RE" -c $C -s $L-g5 -q "$Q/h40" --set max_parallel_workers=0 --tag serial-$t findCompletedRecordsBefore@cutpct=$cp deleteCompletedRecords_selectIds@cutpct=$cp
    "$RE" -c $C -s $L-g5 -q "$Q/h41" --tag $t-limit findCompletedRecordsBefore@cutpct=$cp deleteCompletedRecords_selectIds@cutpct=$cp
  done
  "$RE" -c $C -s $L-g5 -q "$Q/h41" --generic --tag $t-limit findCompletedRecordsBefore@cutpct=0.1 findCompletedRecordsBefore@cutpct=0.9
  "$RE" -c $C -s $L-g5 -q "$Q/h41" --tag $t-limit findFailedRecordsBefore deleteFailedRecords_selectIds
  "$RE" -c $C -s $L-g5 -q "$Q/orig" --set max_parallel_workers=0 --tag serial-$t findFailedRecordsBefore deleteFailedRecords_selectIds
  "$RE" -c $C -s $L-g5 -q "$Q/h42" --tag $t deleteCompletedRecords_single@cutpct=0.1 deleteCompletedRecords_single@cutpct=0.9 deleteFailedRecords_single
  for cp in 0.1 0.9; do
    docker exec $C mkdir -p /harness/m; docker cp -q "$DB/harness/queries/_params.sql" $C:/harness/m/; docker cp -q "$DB/experiments/H40/manual/maxrows-completed.sql" $C:/harness/m/
    { echo "## $t cutpct=$cp limit=1000"; docker exec -i $C psql -U postgres -X -v cutpct=$cp -v limit=1000 -f /harness/m/maxrows-completed.sql 2>&1 | grep -E "Scan|Sort|Time:|Gather|Limit|Seq"; } >> "$DB/results/$L-g5/maxrows-emulation.txt"
  done
}
state() { # name  [do_g5] ; assumes DDL applied
  va; sizes $1; g4 $1; g4vm $1; inc $1; noown $1; hot $1; [[ -n "$G5" ]] && g5 $1; echo "== state $1 done"; }
SC="idx_outbox_record_status_created"
echo "== h08a"; PSQLC < "$DB/experiments/H08/indexes.sql"; state h08a
echo "== h32-psr (drop partition_status_retry)"; ddl "DROP INDEX idx_outbox_record_partition_status_retry"; state h32psr
echo "== h32ii (+incomplete_partition_created INCLUDE status)"; ddl "CREATE INDEX idx_outbox_record_incomplete_partition_created ON outbox_record (partition_no, created_at, id) INCLUDE (status) WHERE completed_at IS NULL"; state h32ii
echo "== h32ii-nostatus (also drop idx_status)"; ddl "DROP INDEX idx_outbox_record_status"; state h32ii-nostatus
echo "== h31a-combo (reset; status->(status,created_at), psr dropped, no ii)"
ddl "DROP INDEX idx_outbox_record_incomplete_partition_created"; ddl "CREATE INDEX $SC ON outbox_record (status, created_at)"; state h31a-nopsr
echo "== h31a (psr restored)"; ddl "CREATE INDEX idx_outbox_record_partition_status_retry ON outbox_record (partition_no, status, next_retry_at)"; state h31a
echo "== h31b (status restored, psr -> (partition_no,status,created_at))"
ddl "DROP INDEX $SC"; ddl "CREATE INDEX idx_outbox_record_status ON outbox_record (status)"; ddl "DROP INDEX idx_outbox_record_partition_status_retry"
ddl "CREATE INDEX idx_outbox_record_partition_status_created ON outbox_record (partition_no, status, created_at)"; state h31b
echo "== h40add"; ddl "DROP INDEX idx_outbox_record_partition_status_created"; ddl "CREATE INDEX idx_outbox_record_partition_status_retry ON outbox_record (partition_no, status, next_retry_at)"
ddl "CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at)"; state h40add
echo "== h40repl"; ddl "DROP INDEX idx_outbox_record_status"; state h40repl
echo "== h07"; ddl "DROP INDEX idx_outbox_record_status_completed"; ddl "CREATE INDEX idx_outbox_record_status ON outbox_record (status)"
ddl "CREATE INDEX idx_outbox_record_completed_created ON outbox_record (completed_at, created_at, id)"; state h07
echo "scenario $S done"
