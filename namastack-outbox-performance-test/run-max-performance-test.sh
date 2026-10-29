#!/usr/bin/env bash
set -euo pipefail

# Native JDBC profile; screening/confirmation results and workload are documented in README.md.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PERF_VIRTUAL_THREADS=true
export NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT="${NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT:-1024}"
export PERF_DB_POOL_SIZE="${PERF_DB_POOL_SIZE:-12}"
export PERF_DRAIN_ON_FULL_BATCH=true
export PERF_NATIVE_COMPLETION_BATCHING=true
export PERF_NATIVE_COMPLETION_BATCH_SIZE="${PERF_NATIVE_COMPLETION_BATCH_SIZE:-256}"
export PERF_NATIVE_COMPLETION_BATCH_WAIT="${PERF_NATIVE_COMPLETION_BATCH_WAIT:-250us}"
export PERF_NATIVE_RECORD_FETCH_BATCH_SIZE="${PERF_NATIVE_RECORD_FETCH_BATCH_SIZE:-1000}"
export PERF_NATIVE_MAX_RECORDS_PER_KEY="${PERF_NATIVE_MAX_RECORDS_PER_KEY:-0}"
export PERF_PG_KEY_STATUS_INDEX=true
export PERF_LIGHTWEIGHT_COLLECTOR=true
export PERF_DURABLE_STOP_COUNTS=true
export PERF_VERIFY_COMPLETION_ORDER=true
# Keep experimental repository paths and comparison modes out of this profile.
export PERF_TUNING_REPOSITORY=false
export PERF_FULL_ROW_COMPLETION=false
export PERF_COMPLETION_BATCH_SIZE=0
export PERF_PG_READY_ORDER_INDEX=false
export PERF_PG_STAT_STATEMENTS=false

exec bash "$ROOT_DIR/run-docker-performance-test.sh" \
  --profile=native-max --warmup-records=250000 --producer-workers=8 \
  --producer-rate=100000 --producer-duration=5m --run-timeout=10m "$@"
