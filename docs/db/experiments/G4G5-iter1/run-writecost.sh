#!/usr/bin/env bash
# Write cost per index set ON TOP OF H08a (fresh container, pre-seed DDL). Usage: run-writecost.sh <S> <set>...
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; E="$DB/experiments"
S="$1"; shift; C=outbox-db-H4x; OUT="$DB/results/G4G5-wc-$S"; mkdir -p "$OUT"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
T="$D/ddl"; mkdir -p "$T"
w() { echo "$2" > "$T/$1.sql"; }
w h08a ""
w h32psr "DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;"
w h32ii "DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry; CREATE INDEX idx_outbox_record_incomplete_partition_created ON outbox_record (partition_no, created_at, id) INCLUDE (status) WHERE completed_at IS NULL;"
w h31a "DROP INDEX IF EXISTS idx_outbox_record_status; CREATE INDEX idx_outbox_record_status_created ON outbox_record (status, created_at);"
w h31a-nopsr "DROP INDEX IF EXISTS idx_outbox_record_status; DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry; CREATE INDEX idx_outbox_record_status_created ON outbox_record (status, created_at);"
w h31b "DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry; CREATE INDEX idx_outbox_record_partition_status_created ON outbox_record (partition_no, status, created_at);"
w h40add "CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at);"
w h40repl "DROP INDEX IF EXISTS idx_outbox_record_status; CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at);"
w h07 "CREATE INDEX idx_outbox_record_completed_created ON outbox_record (completed_at, created_at, id);"
w nostatus "DROP INDEX IF EXISTS idx_outbox_record_status;"
w h40p "CREATE INDEX idx_outbox_record_completed_partial ON outbox_record (completed_at) WHERE status = 'COMPLETED';"
w nostatus-psr "DROP INDEX IF EXISTS idx_outbox_record_status; DROP INDEX IF EXISTS idx_outbox_record_partition_status_retry;"
for set in "$@"; do
  [[ -f "$T/$set.sql" ]] || { echo "unknown set $set" >&2; continue; }
  docker rm -f -v $C >/dev/null 2>&1; "$HS/start-postgres.sh" H4x "$S" --ddl "$E/H08/indexes.sql" --ddl "$T/$set.sql" >/dev/null || exit 1
  "$HS/write-cost.sh" $C -o "$OUT/$set.txt" >/dev/null; echo "$set: $(grep -E '^(insert|update)' "$OUT/$set.txt" | sed 's/clients=4 //' | tr '\n' ' ')"; docker rm -f -v $C >/dev/null
done
