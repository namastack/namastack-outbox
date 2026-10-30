#!/usr/bin/env bash
# Starts a throwaway Postgres container `outbox-db-<name>`, applies the repository DDL and
# optionally seeds a scenario.
#
# Usage: start-postgres.sh <name> [scenario] [--ddl extra.sql]... [--post-seed-ddl extra.sql]... [--keep-existing]
#   <name>            container suffix -> container `outbox-db-<name>`
#   [scenario]        A|B|C|D|E|edge|none (default none). Loads harness/seed-<scenario>.sql
#   --ddl FILE        extra SQL applied right after the repository schema, BEFORE seeding
#                     (e.g. create/drop indexes so they are built incrementally like in production)
#   --post-seed-ddl FILE  extra SQL applied AFTER seeding, before VACUUM ANALYZE
#                     (faster: CREATE INDEX on loaded data; physically more compact than incremental)
#   --schema FILE     alternative base schema (default: repo postgres/outbox-tables.sql)
#
# Env: PG_IMAGE (default postgres:18-alpine), PG_SHARED_BUFFERS (default 1GB),
#      PG_EXTRA_ARGS (extra `-c key=value` server args),
#      PG_TMPFS (default 6g): data directory on a tmpfs of this size (no disk use, no anonymous volume
#      left behind); PG_TMPFS= (empty) uses a regular Docker volume instead (remove with docker rm -f -v).
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"
SCHEMA="$REPO_DIR/namastack-outbox-jdbc/src/main/resources/schema/postgres/outbox-tables.sql"

NAME="${1:?usage: start-postgres.sh <name> [scenario] [--ddl file] [--post-seed-ddl file]}"
shift
SCENARIO="none"
if [[ $# -gt 0 && "$1" != --* ]]; then SCENARIO="$1"; shift; fi
PRE_DDL=()
POST_DDL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ddl) PRE_DDL+=("$(realpath "$2")"); shift 2 ;;
    --post-seed-ddl) POST_DDL+=("$(realpath "$2")"); shift 2 ;;
    --schema) SCHEMA="$(realpath "$2")"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

CONTAINER="outbox-db-$NAME"
IMAGE="${PG_IMAGE:-postgres:18-alpine}"
PSQL=(docker exec -i "$CONTAINER" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q -X)

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "container $CONTAINER already exists; remove it first (docker rm -f $CONTAINER)" >&2
  exit 1
fi

# shared_buffers is raised so a 1M-row table (~0.8 GB) fits; with the 128MB default most
# reads would be OS page cache hits reported as "read". --shm-size is needed for parallel hash.
# shellcheck disable=SC2086
TMPFS_ARGS=()
if [[ -n "${PG_TMPFS-6g}" ]]; then TMPFS_ARGS=(--tmpfs "/var/lib/postgresql:rw,size=${PG_TMPFS-6g}"); fi
docker run -d --rm --name "$CONTAINER" --shm-size=1g "${TMPFS_ARGS[@]}" -e POSTGRES_PASSWORD=pw "$IMAGE" \
  -c shared_buffers="${PG_SHARED_BUFFERS:-1GB}" \
  -c work_mem=4MB \
  -c max_wal_size=1GB \
  -c checkpoint_timeout=30min \
  -c jit=off \
  -c track_io_timing=on \
  ${PG_EXTRA_ARGS:-} >/dev/null

trap 'echo "start failed, removing $CONTAINER" >&2; docker rm -f -v "$CONTAINER" >/dev/null 2>&1' ERR
echo -n "waiting for $CONTAINER"
for _ in $(seq 1 120); do
  # pg_isready can succeed during the init-script phase; require a real query on the final server
  if docker exec "$CONTAINER" psql -U postgres -tAc "select 1" >/dev/null 2>&1 \
     && docker logs "$CONTAINER" 2>&1 | grep -q "PostgreSQL init process complete"; then
    sleep 1
    if docker exec "$CONTAINER" psql -U postgres -tAc "select 1" >/dev/null 2>&1; then break; fi
  fi
  echo -n "."; sleep 1
done
echo " ready"

echo "applying schema $SCHEMA"
"${PSQL[@]}" < "$SCHEMA"
"${PSQL[@]}" < "$HARNESS_DIR/bench-setup.sql"
for f in "${PRE_DDL[@]}"; do echo "applying pre-seed DDL $f"; "${PSQL[@]}" < "$f"; done

if [[ "$SCENARIO" != "none" ]]; then
  SEED="$HARNESS_DIR/seed-$SCENARIO.sql"
  [[ -f "$SEED" ]] || { echo "no seed file $SEED" >&2; exit 1; }
  echo "seeding scenario $SCENARIO"
  start=$(date +%s)
  "${PSQL[@]}" < "$SEED"
  echo "seeded in $(( $(date +%s) - start ))s"
fi
for f in "${POST_DDL[@]}"; do echo "applying post-seed DDL $f"; "${PSQL[@]}" < "$f"; done

"${PSQL[@]}" -c "VACUUM ANALYZE" -c "CHECKPOINT"
"${PSQL[@]}" -c "SELECT pg_stat_reset()" >/dev/null
"${PSQL[@]}" -tA -c "SELECT 'rows=' || count(*) || ' new=' || count(*) FILTER (WHERE status='NEW') || ' failed=' || count(*) FILTER (WHERE status='FAILED') || ' keys_with_new=' || count(DISTINCT record_key) FILTER (WHERE status='NEW') FROM outbox_record"
echo "container $CONTAINER ready (scenario $SCENARIO)"
