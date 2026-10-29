#!/usr/bin/env bash
set -euo pipefail

# Docker Desktop profile measured with a 32 GB VM. Keep Java clients on the database network.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PERF_JAVA_IMAGE="${PERF_JAVA_IMAGE:-eclipse-temurin:21-jre@sha256:f37956012e0cb88400e5b3689aeae2d2d735364c1922ae2225536163b5a99f56}"
export PERF_DB_POOL_SIZE="${PERF_DB_POOL_SIZE:-28}"
export PERF_PG_SHARED_BUFFERS="${PERF_PG_SHARED_BUFFERS:-8GB}"
export PERF_PG_EFFECTIVE_CACHE_SIZE="${PERF_PG_EFFECTIVE_CACHE_SIZE:-24GB}"
export PERF_PG_MAX_WAL_SIZE="${PERF_PG_MAX_WAL_SIZE:-16GB}"
export PERF_PG_MIN_WAL_SIZE="${PERF_PG_MIN_WAL_SIZE:-2GB}"
export PERF_PG_WAL_BUFFERS="${PERF_PG_WAL_BUFFERS:-64MB}"
export PERF_PG_TRACK_IO_TIMING="${PERF_PG_TRACK_IO_TIMING:-on}"
export PERF_PG_TRACK_WAL_IO_TIMING="${PERF_PG_TRACK_WAL_IO_TIMING:-on}"
export PERF_VERIFY_COMPLETION_ORDER="${PERF_VERIFY_COMPLETION_ORDER:-true}"
export PERF_PG_CLIENT_CONNECTION_CHECK_INTERVAL="${PERF_PG_CLIENT_CONNECTION_CHECK_INTERVAL:-1s}"

exec bash "$ROOT_DIR/run-tuned-performance-test.sh" \
  --profile=docker-internal-network --instances=8 --producer-rate=20000 "$@"
