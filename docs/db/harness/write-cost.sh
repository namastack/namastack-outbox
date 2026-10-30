#!/usr/bin/env bash
# Write-cost benchmark (section 5.7): N INSERTs (create) then N updateProcessingState (NEW -> COMPLETED)
# of exactly those rows, with pgbench -M prepared (server-side prepared statements, like pgjdbc).
# MUTATES DATA (adds N rows). Run it on a seeded container after the read benchmarks.
#
# Usage: write-cost.sh <container> [-n 50000] [-c 4] [-o out.txt]
# Reports per phase: tps, avg latency, ms per 1k ops, WAL bytes per op; plus index sizes before/after.
# synchronous_commit=off for the pgbench sessions, so the result reflects index maintenance and WAL
# volume rather than fsync latency. WAL bytes/op is the most stable signal.
set -euo pipefail
CONTAINER="${1:?usage: write-cost.sh <container> [-n N] [-c clients] [-o out.txt]}"; shift
N=50000; C=4; OUTFILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) N="$2"; shift 2 ;;
    -c) C="$2"; shift 2 ;;
    -o) OUTFILE="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
T=$(( N / C ))
PSQL=(docker exec -i "$CONTAINER" psql -U postgres -d postgres -X -v ON_ERROR_STOP=1 -tA)
"${PSQL[@]}" -q <<'SQL'
SET client_min_messages = warning; DROP SEQUENCE IF EXISTS wc_ins; DROP SEQUENCE IF EXISTS wc_upd;
CREATE SEQUENCE wc_ins; CREATE SEQUENCE wc_upd;
SQL
# continue numbering if the script already ran on this container
prev="$("${PSQL[@]}" -c "SELECT count(*) FROM outbox_record WHERE record_type = 'com.example.WriteCost'")"
"${PSQL[@]}" -q -c "SELECT setval('wc_ins', $prev + 1, false), setval('wc_upd', $prev + 1, false)" >/dev/null

docker exec -i "$CONTAINER" sh -c 'mkdir -p /harness && cat > /harness/wc-insert.sql' <<'EOF2'
\set k random(1, 50000)
SELECT md5('wc-' || nextval('wc_ins'))::uuid::text AS id \gset
INSERT INTO outbox_record (id, status, record_key, record_type, payload, context, partition_no, created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id) VALUES (:id, 'NEW', 'wc-' || :k, 'com.example.WriteCost', repeat('x', 500), '{}', abs(hashtext('wc-' || :k)) % 256, clock_timestamp(), NULL, 0, NULL, clock_timestamp(), 'com.example.Handler#handle');
EOF2
docker exec -i "$CONTAINER" sh -c 'cat > /harness/wc-update.sql' <<'EOF2'
SELECT md5('wc-' || nextval('wc_upd'))::uuid::text AS id \gset
UPDATE outbox_record SET status = 'COMPLETED', completed_at = clock_timestamp(), failure_count = 0, failure_reason = NULL, next_retry_at = clock_timestamp() WHERE id = :id;
EOF2

sizes() {
  "${PSQL[@]}" -F' ' -c "SELECT string_agg(indexrelname || '=' || pg_relation_size(indexrelid) / 8192, ' ' ORDER BY indexrelname),
    (sum(pg_relation_size(indexrelid)) / 8192)::bigint, pg_relation_size('outbox_record') / 8192
    FROM pg_stat_user_indexes WHERE relname = 'outbox_record'"
}
phase() { # <label> <script>
  local lsn0 lsn1 out tps lat wal
  "${PSQL[@]}" -q -c "CHECKPOINT"
  lsn0="$("${PSQL[@]}" -c "SELECT pg_current_wal_lsn()")"
  out="$(docker exec -e PGOPTIONS='-c synchronous_commit=off' "$CONTAINER" pgbench -U postgres -n -M prepared -c "$C" -j "$C" -t "$T" --random-seed=42 -f "$2" postgres 2>&1)"
  lsn1="$("${PSQL[@]}" -c "SELECT pg_current_wal_lsn()")"
  wal="$("${PSQL[@]}" -c "SELECT pg_wal_lsn_diff('$lsn1', '$lsn0')::bigint")"
  tps="$(grep -oP 'tps = \K[\d.]+' <<< "$out" | head -1)"
  lat="$(grep -oP 'latency average = \K[\d.]+' <<< "$out")"
  printf '%-8s ops=%d clients=%d tps=%s avg_latency_ms=%s ms_per_1k_ops=%.1f wal_bytes_per_op=%d\n' \
    "$1" "$N" "$C" "$tps" "$lat" "$(echo "1000000 / $tps" | bc -l)" "$(( wal / N ))"
}
{
  echo "index_pages_before: $(sizes)"
  phase insert /harness/wc-insert.sql
  phase update /harness/wc-update.sql
  echo "index_pages_after:  $(sizes)"
  echo "(sizes: per-index pages, total index pages, heap pages; 8 KiB pages)"
} | tee ${OUTFILE:+"$OUTFILE"}
