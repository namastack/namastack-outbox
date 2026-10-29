#!/usr/bin/env bash
set -euo pipefail

# Opt-in profile measured on PostgreSQL 17 with Java 21 virtual threads.
# Arguments supplied by the caller override the screening defaults below.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PERF_POSTGRES_PORT="${PERF_POSTGRES_PORT:-55432}"
export PERF_PROMETHEUS_PORT="${PERF_PROMETHEUS_PORT:-59090}"
export PERF_GRAFANA_PORT="${PERF_GRAFANA_PORT:-53000}"
export PERF_POSTGRES_EXPORTER_PORT="${PERF_POSTGRES_EXPORTER_PORT:-59187}"
export PERF_DB_URL="${PERF_DB_URL:-jdbc:postgresql://localhost:$PERF_POSTGRES_PORT/outbox_performance_test?reWriteBatchedInserts=true}"
export PERF_COMPOSE_OVERRIDE="$ROOT_DIR/docker-compose.tuning.yml"
export PERF_VIRTUAL_THREADS="${PERF_VIRTUAL_THREADS:-true}"
export NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT="${NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT:-20}"
export PERF_DB_POOL_SIZE="${PERF_DB_POOL_SIZE:-30}"
export PERF_SETTLE_RETAINED_RECORDS="${PERF_SETTLE_RETAINED_RECORDS:-true}"
export PERF_PG_MAX_WAL_SIZE="${PERF_PG_MAX_WAL_SIZE:-8GB}"
export PERF_PG_MIN_WAL_SIZE="${PERF_PG_MIN_WAL_SIZE:-1GB}"
export PERF_PG_WAL_BUFFERS="${PERF_PG_WAL_BUFFERS:-16MB}"
export PERF_PG_WAL_COMPRESSION="${PERF_PG_WAL_COMPRESSION:-lz4}"
export PERF_PG_SHARED_BUFFERS="${PERF_PG_SHARED_BUFFERS:-1GB}"
export PERF_PG_WORK_MEM="${PERF_PG_WORK_MEM:-16MB}"
export PERF_PG_JIT="${PERF_PG_JIT:-off}"
export PERF_PG_AUTOVACUUM_NAPTIME="${PERF_PG_AUTOVACUUM_NAPTIME:-10s}"
export PERF_PG_TUNE_AUTOVACUUM="${PERF_PG_TUNE_AUTOVACUUM:-true}"
export PERF_PG_PLAN_CACHE_MODE="${PERF_PG_PLAN_CACHE_MODE:-auto}"

exec bash "$ROOT_DIR/run-performance-test.sh" \
  --mode=steady-state --profile=tuned-virtual-threads \
  --instances=6 --records-per-key=10 --warmup-records=25000 \
  --retained-records=4000000 --batch-size=1000 --poll-interval=25ms \
  --producer-rate=16000 --producer-duration=90s \
  --producer-batch-size=100 --producer-workers=4 \
  --measurement-warmup=30s --sample-interval=15s --run-timeout=5m "$@"
