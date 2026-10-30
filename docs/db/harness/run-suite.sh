#!/usr/bin/env bash
# Full suite for one scenario and one index set: start container, EXPLAIN all queries (custom,
# generic, serial, costs), sizes, workload mix, write cost, remove container.
#
# Usage: run-suite.sh <label> <scenario> [-q queries-dir] [--ddl f] [--post-seed-ddl f] [--skip-writes] [--keep]
#   label     results go to results/<label>/ and the container is outbox-db-<label>
#             (baseline uses label base-<scenario> -> results/base-<scenario>/)
#   --ddl / --post-seed-ddl   passed to start-postgres.sh (index set under test)
#   --skip-writes             skip workload mix and write cost
#   --keep                    keep the container running afterwards
set -euo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LABEL="${1:?label}"; SCEN="${2:?scenario}"; shift 2
START_ARGS=(); QARGS=(); SKIP_WRITES=0; KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ddl|--post-seed-ddl) START_ARGS+=("$1" "$2"); shift 2 ;;
    -q) QARGS=(-q "$2"); shift 2 ;;
    --skip-writes) SKIP_WRITES=1; shift ;;
    --keep) KEEP=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
C="outbox-db-$LABEL"
OUT="$(cd "$H/.." && pwd)/results/$LABEL"
rm -rf "$OUT"; mkdir -p "$OUT"
[[ $KEEP -eq 1 ]] || trap 'docker rm -f -v "$C" >/dev/null 2>&1 || true' EXIT
set -o pipefail
"$H/start-postgres.sh" "$LABEL" "$SCEN" "${START_ARGS[@]}" | tee "$OUT/start.log"

docker exec -i "$C" psql -U postgres -X -P pager=off <<'SQL' > "$OUT/sizes.txt"
SELECT c.relname, c.relkind, pg_relation_size(c.oid) AS bytes, pg_size_pretty(pg_relation_size(c.oid)) AS size,
       c.reltuples::bigint AS reltuples
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND (c.relname = 'outbox_record' OR c.oid IN
      (SELECT indexrelid FROM pg_index WHERE indrelid = 'outbox_record'::regclass))
ORDER BY c.relkind DESC, c.relname;
SELECT status, count(*), count(DISTINCT record_key) AS keys FROM outbox_record GROUP BY status ORDER BY status;
SQL

R="$H/run-explain.sh"
"$R" -c "$C" -s "$LABEL" "${QARGS[@]}"
"$R" -c "$C" -s "$LABEL" "${QARGS[@]}" --generic
# withMaxRows queries as pgjdbc executes them: parallel workers are never launched (see 01-baseline.md)
"$R" -c "$C" -s "$LABEL" "${QARGS[@]}" --set max_parallel_workers=0 --tag serial \
  findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter \
  findCompletedRecordsBefore findFailedRecordsBefore deleteCompletedRecords_selectIds deleteFailedRecords_selectIds
# generic plan executed like pgjdbc with maxRows (no workers)
"$R" -c "$C" -s "$LABEL" "${QARGS[@]}" --generic --set max_parallel_workers=0 --tag serial \
  findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter
# estimates vs actuals for hot path and metrics
"$R" -c "$C" -s "$LABEL" "${QARGS[@]}" --costs -r 1 \
  findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findIncompleteRecordPages \
  findOldestDueNextRetryAtByPartitions_filter findOldestDueNextRetryAtByPartitions_nofilter \
  findOldestCreatedAtByPartitions countRecordsByPartitions countByStatus findCompletedRecordsBefore

if [[ $SKIP_WRITES -eq 0 ]]; then
  "$H/workload-mix.sh" "$C" -o "$OUT/index-usage.txt"
  "$H/write-cost.sh" "$C" -o "$OUT/write-cost.txt"
fi
echo "suite done: $OUT"
