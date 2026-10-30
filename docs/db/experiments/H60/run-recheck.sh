#!/usr/bin/env bash
# Noise recheck on D: S2 vs S1 (in place, 7 runs) for the hot queries flagged in the first pass.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/queries"; L=H60-recheck-D; C=outbox-db-$L
rm -rf "$DB/results/$L"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT; docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" $L D --ddl "$D/indexes-S2.sql" >/dev/null || exit 1
P() { docker exec -i $C psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
m() { "$HS/run-explain.sh" -c $C -s $L -q "$Q" -r 7 --set max_parallel_workers=0 --tag serial-$1 findRecordKeysInPartitions_filter findOldestDueNextRetryAtByPartitions_filter findRecordKeysInPartitions_nofilter
      "$HS/run-explain.sh" -c $C -s $L -q "$Q" -r 7 --tag $1 findIncompleteRecordPages findOldestCreatedAtByPartitions; }
m S2a; m S2b
P -c "CREATE INDEX idx_outbox_record_record_key_completed_created ON outbox_record (record_key, completed_at, created_at)" -c "DROP INDEX idx_outbox_record_record_key_completed_created_id" -c "VACUUM ANALYZE outbox_record"
m S1a; m S1b
