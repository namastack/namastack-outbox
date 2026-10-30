#!/usr/bin/env bash
# Index-usage workload mix (section 5.6). MUTATES DATA - run last on a container.
# Usage: workload-mix.sh <container> [-f mix.sql] [-o out.txt] [--var k=v]...
#   vars: parts (64), polls (100), batch (10), scrapes (10), filter (true), limit (1000)
# Resets statistics, runs the mix, prints pg_stat_user_indexes / pg_stat_user_tables for outbox_record.
set -euo pipefail
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${1:?usage: workload-mix.sh <container> [-f mix.sql] [-o out.txt] [--var k=v]...}"; shift
MIX="$HARNESS_DIR/workload-mix.sql"
OUTFILE=""
VARS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f) MIX="$(realpath "$2")"; shift 2 ;;
    -o) OUTFILE="$2"; shift 2 ;;
    --var) VARS+=(-v "$2"); shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
PSQL=(docker exec -i "$CONTAINER" psql -U postgres -d postgres -X -v ON_ERROR_STOP=1)
cutoffs="$("${PSQL[@]}" -tA -F'|' -c "
  SELECT coalesce((SELECT percentile_disc(0.1) WITHIN GROUP (ORDER BY completed_at) FROM outbox_record WHERE status = 'COMPLETED')::text, ''),
         coalesce((SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY next_retry_at) FROM outbox_record WHERE status = 'FAILED')::text, '')")"
cc="$(cut -d'|' -f1 <<< "$cutoffs")"; fc="$(cut -d'|' -f2 <<< "$cutoffs")"
"${PSQL[@]}" -q -c "SELECT pg_stat_reset()" >/dev/null
sleep 1
start=$(date +%s.%N)
"${PSQL[@]}" -q "${VARS[@]}" -v completed_cutoff="$cc" -v failed_cutoff="$fc" < "$MIX" 2>&1 | grep -v '^$' | grep -v pg_stat_force_next_flush | grep -v -- '----' | grep -v '(1 row)' | grep -v '^ *$' || true
elapsed=$(echo "$(date +%s.%N) - $start" | bc)
sleep 2
report="$("${PSQL[@]}" -P pager=off <<'SQL'
SELECT indexrelname AS index, idx_scan, idx_tup_read, idx_tup_fetch,
       pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname = 'outbox_record' ORDER BY indexrelname;
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del
FROM pg_stat_user_tables WHERE relname = 'outbox_record';
SQL
)"
echo "elapsed ${elapsed}s"
echo "$report"
if [[ -n "$OUTFILE" ]]; then { echo "# workload-mix $(basename "$MIX") ${VARS[*]:-} elapsed ${elapsed}s"; echo "$report"; } > "$OUTFILE"; fi
