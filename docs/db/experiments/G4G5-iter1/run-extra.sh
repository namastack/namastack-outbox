#!/usr/bin/env bash
# Extra: (1) H32 oldest worst case on H08a (INC noowned); (2) H40p partial (completed_at) WHERE status='COMPLETED' on H08a. Usage: run-extra.sh <S>
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; C=outbox-db-H3x; RE="$HS/run-explain.sh"; L="G4G5x-$S"; rm -rf "$DB/results/$L"
docker rm -f -v $C >/dev/null 2>&1; "$HS/start-postgres.sh" H3x "$S" --ddl "$DB/experiments/H08/indexes.sql" >/dev/null || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
"$RE" -c $C -s $L -q "$Q/extra" --tag h08a findOldestCreatedAtByPartitions_noowned_inc
"$RE" -c $C -s $L -q "$Q/h31" --tag h08a findOldestCreatedAtByPartitions_noowned
"$RE" -c $C -s $L -q "$Q/h32" --tag h08a findOldestCreatedAtByPartitions_lateral
PSQLC -c "CREATE INDEX idx_outbox_record_completed_partial ON outbox_record (completed_at) WHERE status = 'COMPLETED'" -c "VACUUM ANALYZE outbox_record"
PSQLC -c "\di+ idx_outbox_record_completed_partial" | tail -3 > "$DB/results/$L/size-h40p.txt"
for cp in 0.1 0.9; do
  "$RE" -c $C -s $L -q "$Q/h41" --tag h40p-limit findCompletedRecordsBefore@cutpct=$cp deleteCompletedRecords_selectIds@cutpct=$cp
  "$RE" -c $C -s $L -q "$Q/h41" --generic --tag h40p-limit findCompletedRecordsBefore@cutpct=$cp
  "$RE" -c $C -s $L -q "$Q/h40" --set max_parallel_workers=0 --tag serial-h40p findCompletedRecordsBefore@cutpct=$cp
  docker exec $C mkdir -p /harness/m; docker cp -q "$DB/harness/queries/_params.sql" $C:/harness/m/; docker cp -q "$DB/experiments/H40/manual/maxrows-completed.sql" $C:/harness/m/
  { echo "## h40p cutpct=$cp limit=1000"; docker exec -i $C psql -U postgres -X -v cutpct=$cp -v limit=1000 -f /harness/m/maxrows-completed.sql 2>&1 | grep -E "Scan|Sort|Time:|Gather|Limit|Seq"; } >> "$DB/results/$L/maxrows-emulation.txt"
done
"$RE" -c $C -s $L -q "$Q/orig" --tag h40p countByStatus@status=COMPLETED
"$RE" -c $C -s $L -q "$Q/orig" --generic --tag h40p countByStatus@status=COMPLETED
