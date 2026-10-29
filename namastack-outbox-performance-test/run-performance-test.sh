#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${PERF_PG_STAT_STATEMENTS:-false}" == "true" ]]; then
  export PERF_PG_SHARED_PRELOAD_LIBRARIES="${PERF_PG_SHARED_PRELOAD_LIBRARIES:-pg_stat_statements}"
fi
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml")
if [[ -n "${PERF_COMPOSE_OVERRIDE:-}" ]]; then
  COMPOSE+=(-f "$PERF_COMPOSE_OVERRIDE")
fi
GRADLE=("$ROOT_DIR/../gradlew" -p "$ROOT_DIR")
TOOL="$ROOT_DIR/tooling/build/install/tooling/bin/tooling"
CONSUMER_JAR="$ROOT_DIR/consumer/build/libs/namastack-outbox-performance-test-consumer.jar"
TARGETS_FILE="$ROOT_DIR/monitoring/prometheus/targets/consumer-targets.json"
RUNTIME_DIR="$ROOT_DIR/runtime"
LOG_DIR="$RUNTIME_DIR/logs"
PID_DIR="$RUNTIME_DIR/pids"

MODE="steady-state"
PROFILE=""
INSTANCES=4
RECORDS=1000000
RECORDS_PER_KEY=1
WARMUP_RECORDS=25000
RETAINED_RECORDS=0
BATCH_SIZE=1000
POLL_INTERVAL="100ms"
PRODUCER_RATE=3000
PRODUCER_DURATION="10m"
PRODUCER_BATCH_SIZE=1
PRODUCER_WORKERS=4
MEASUREMENT_WARMUP="30s"
MIN_PRODUCER_RATE_RATIO="0.99"
MAX_BACKLOG_GROWTH_RATE=""
MAX_END_BACKLOG=""
TRIGGER_TIMEOUT="15m"
RUN_TIMEOUT="30m"
SAMPLE_INTERVAL="5s"
KEEP_MONITORING=false
SKIP_BUILD=false
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
POSTGRES_PORT="${PERF_POSTGRES_PORT:-5432}"
PROMETHEUS_PORT="${PERF_PROMETHEUS_PORT:-9090}"
GRAFANA_PORT="${PERF_GRAFANA_PORT:-3000}"
export PERF_DB_URL="${PERF_DB_URL:-jdbc:postgresql://localhost:$POSTGRES_PORT/outbox_performance_test}"

usage() {
  cat <<'EOF'
Usage: ./run-performance-test.sh [options]

Options:
  --mode=<name>                steady-state or backlog-drain (default: steady-state)
  --profile=<name>             Report profile name (default depends on mode)
  --instances=<count>          Consumer process count (default: 4)
  --records=<count>            Backlog-drain record count (default: 1000000)
  --records-per-key=<count>    Events sharing one ordering key (default: 1)
  --warmup-records=<count>     Warm-up records, 0 disables warm-up (default: 25000)
  --retained-records=<count>   Preload completed records for a short steady-state aged-table test (default: 0)
  --batch-size=<count>         Maximum keys per consumer polling cycle (default: 1000)
  --poll-interval=<duration>   Fixed consumer polling delay (default: 100ms)
  --producer-rate=<count>      Steady-state target records per second (default: 3000)
  --producer-duration=<time>   Steady-state configured production duration (default: 10m)
  --producer-batch-size=<count>
                               Records committed per producer transaction (default: 1)
  --producer-workers=<count>   Parallel JDBC producer connections (default: 4)
  --measurement-warmup=<time>  Initial stabilization window excluded from capacity evaluation (default: 30s)
  --min-producer-rate-ratio=<ratio>
                               Minimum accepted actual / target producer rate (default: 0.99)
  --max-backlog-growth-rate=<count>
                               Sustainable backlog-growth limit; default is 1% of producer rate
  --max-end-backlog=<count>    Sustainable end-backlog limit; default is 2 seconds of producer load
  --trigger-timeout=<duration> Backlog INSERT SELECT timeout (default: 15m)
  --run-timeout=<duration>     Final drain timeout (default: 30m)
  --sample-interval=<duration> PostgreSQL sampling interval (default: 5s)
  --run-id=<id>                Explicit report run ID
  --keep-monitoring=true       Leave Docker Compose services running after the test
  --skip-build=true            Reuse existing binaries
  --help                       Show this help
EOF
}

for argument in "$@"; do
  case "$argument" in
    --mode=*) MODE="${argument#*=}" ;;
    --profile=*) PROFILE="${argument#*=}" ;;
    --instances=*) INSTANCES="${argument#*=}" ;;
    --records=*) RECORDS="${argument#*=}" ;;
    --records-per-key=*) RECORDS_PER_KEY="${argument#*=}" ;;
    --warmup-records=*) WARMUP_RECORDS="${argument#*=}" ;;
    --retained-records=*) RETAINED_RECORDS="${argument#*=}" ;;
    --batch-size=*) BATCH_SIZE="${argument#*=}" ;;
    --poll-interval=*) POLL_INTERVAL="${argument#*=}" ;;
    --producer-rate=*) PRODUCER_RATE="${argument#*=}" ;;
    --producer-duration=*) PRODUCER_DURATION="${argument#*=}" ;;
    --producer-batch-size=*) PRODUCER_BATCH_SIZE="${argument#*=}" ;;
    --producer-workers=*) PRODUCER_WORKERS="${argument#*=}" ;;
    --measurement-warmup=*) MEASUREMENT_WARMUP="${argument#*=}" ;;
    --min-producer-rate-ratio=*) MIN_PRODUCER_RATE_RATIO="${argument#*=}" ;;
    --max-backlog-growth-rate=*) MAX_BACKLOG_GROWTH_RATE="${argument#*=}" ;;
    --max-end-backlog=*) MAX_END_BACKLOG="${argument#*=}" ;;
    --trigger-timeout=*) TRIGGER_TIMEOUT="${argument#*=}" ;;
    --run-timeout=*) RUN_TIMEOUT="${argument#*=}" ;;
    --sample-interval=*) SAMPLE_INTERVAL="${argument#*=}" ;;
    --run-id=*) RUN_ID="${argument#*=}" ;;
    --keep-monitoring=*) KEEP_MONITORING="${argument#*=}" ;;
    --skip-build=*) SKIP_BUILD="${argument#*=}" ;;
    --help) usage; exit 0 ;;
    *) echo "Unknown argument: $argument" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$MODE" != "steady-state" && "$MODE" != "backlog-drain" ]]; then
  echo "mode must be steady-state or backlog-drain" >&2
  exit 2
fi
if [[ -z "$PROFILE" ]]; then
  [[ "$MODE" == "steady-state" ]] && PROFILE="steady-state-payments" || PROFILE="independent-payments"
fi
if (( INSTANCES < 1 || RECORDS < 1 || RECORDS_PER_KEY < 1 || WARMUP_RECORDS < 0 || RETAINED_RECORDS < 0 || PRODUCER_RATE < 1 || PRODUCER_BATCH_SIZE < 1 || PRODUCER_WORKERS < 1 )); then
  echo "Numeric counts must be positive; warmup-records may be zero" >&2
  exit 2
fi
if [[ "$MODE" != "steady-state" ]] && (( RETAINED_RECORDS > 0 )); then
  echo "retained-records is only supported in steady-state mode" >&2
  exit 2
fi

if [[ "$MODE" == "steady-state" && "${PERF_VERIFY_COMPLETION_ORDER:-false}" == "true" ]] &&
  (( PRODUCER_WORKERS > 1 && PRODUCER_BATCH_SIZE % RECORDS_PER_KEY != 0 )); then
  echo "Parallel ordering tests require producer-batch-size to be a multiple of records-per-key so a key cannot span producer transactions" >&2
  exit 2
fi

COLLECTOR_PID=""
VERIFY_WAL_SLOT=""
source "$ROOT_DIR/docker-jvm-runtime.sh"

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ -n "$COLLECTOR_PID" ]] && kill -0 "$COLLECTOR_PID" 2>/dev/null; then
    kill "$COLLECTOR_PID" 2>/dev/null || true
    wait "$COLLECTOR_PID" 2>/dev/null || true
  fi
  if [[ -d "$PID_DIR" ]]; then
    while IFS= read -r pid_file; do
      kill "$(cat "$pid_file")" 2>/dev/null || true
    done < <(find "$PID_DIR" -type f -name '*.pid' | sort)
    sleep 2
    while IFS= read -r pid_file; do
      kill -9 "$(cat "$pid_file")" 2>/dev/null || true
    done < <(find "$PID_DIR" -type f -name '*.pid' | sort)
    rm -f "$PID_DIR"/*.pid
  fi
  cleanup_java_runtime
  if (( exit_code != 0 )) && [[ -n "${REPORT_DIR:-}" ]]; then
    cp "$LOG_DIR"/*.log "$REPORT_DIR/logs/" 2>/dev/null || true
    printf 'Runner exited with status %s. Failed runs do not establish healthy capacity; inspect the logs for the failure stage.\n' "$exit_code" > "$REPORT_DIR/failure.txt"
    snapshot_postgres "$REPORT_DIR/postgres-failure.json" 2> "$REPORT_DIR/snapshot-error.log" || true
  fi
  if [[ -n "$VERIFY_WAL_SLOT" ]]; then
    if ! "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U username -d outbox_performance_test \
      -c "SELECT pg_drop_replication_slot('$VERIFY_WAL_SLOT')" > "$REPORT_DIR/verification-wal-cleanup.log" 2>&1; then
      echo "Failed to release benchmark WAL reservation; see $REPORT_DIR/verification-wal-cleanup.log" >&2
      if (( exit_code == 0 )); then exit_code=1; fi
    fi
  fi
  if [[ "$KEEP_MONITORING" != "true" ]]; then
    "${COMPOSE[@]}" down >/dev/null 2>&1 || true
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

wait_for_postgres() {
  local deadline=$((SECONDS + 120))
  until "${COMPOSE[@]}" exec -T postgres pg_isready -U username -d outbox_performance_test >/dev/null 2>&1; do
    (( SECONDS < deadline )) || { echo "Timed out waiting for PostgreSQL" >&2; exit 1; }
    sleep 2
  done
}

wait_for_http() {
  local url=$1
  local label=$2
  local deadline=$((SECONDS + 120))
  until curl --fail --silent "$url" >/dev/null; do
    (( SECONDS < deadline )) || { echo "Timed out waiting for $label at $url" >&2; exit 1; }
    sleep 1
  done
}

tool() {
  run_tool "$@"
}

generate_prometheus_targets() {
  {
    printf '[\n'
    for ((index = 1; index <= INSTANCES; index++)); do
      (( index == 1 )) || printf ',\n'
      local target_host="host.docker.internal"
      [[ -z "${PERF_JAVA_IMAGE:-}" ]] || target_host="$JAVA_CONTAINER_NAME"
      printf '  {"targets":["%s:%s"],"labels":{"consumer":"consumer-%s"}}' "$target_host" "$((9100 + index))" "$index"
    done
    printf '\n]\n'
  } > "$TARGETS_FILE"
}

apply_schema_and_reset() {
  local fillfactor="${PERF_PG_OUTBOX_FILLFACTOR:-100}"
  if [[ ! "$fillfactor" =~ ^[0-9]+$ ]] || (( fillfactor < 50 || fillfactor > 100 )); then
    echo "PERF_PG_OUTBOX_FILLFACTOR must be an integer from 50 to 100" >&2
    exit 2
  fi
  # Discard the previous dataset before rebuilding any indexes missing from an opt-in profile.
  "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U username -d outbox_performance_test <<'SQL'
-- Clear only abandoned reservations owned by this dedicated benchmark.
SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots
WHERE starts_with(slot_name, 'namastack_perf_order_') AND NOT active;
SELECT 'TRUNCATE TABLE outbox_record' WHERE to_regclass('outbox_record') IS NOT NULL
\gexec
SQL
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/00_outbox_schema.sql
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/10_performance_schema.sql
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/20_reset.sql
  "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -v "fillfactor=$fillfactor" -U username -d outbox_performance_test <<'SQL'
ALTER TABLE outbox_record SET (fillfactor = :fillfactor);
SQL
  if [[ "${PERF_PG_KEY_STATUS_INDEX:-false}" == "true" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/28_key_status_index.sql
  fi
  if [[ "${PERF_PG_READY_ORDER_INDEX:-false}" == "true" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/27_ready_order_index.sql
  fi
  if [[ "${PERF_PG_TUNE_AUTOVACUUM:-false}" == "true" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/25_tuned_autovacuum.sql
  fi
  if [[ "${PERF_PG_STAT_STATEMENTS:-false}" == "true" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
  fi
}

snapshot_postgres() {
  "${COMPOSE[@]}" exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/40_snapshot.sql > "$1"
}

snapshot_statements() {
  if [[ "${PERF_PG_STAT_STATEMENTS:-false}" == "true" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/45_statement_snapshot.sql > "$1"
  fi
}

seed() {
  tool seed \
    "--run-id=$1" \
    "--profile=$PROFILE" \
    "--records=$2" \
    "--records-per-key=$RECORDS_PER_KEY" \
    "--instances=$INSTANCES" \
    "--batch-size=$BATCH_SIZE" \
    "--poll-interval=$POLL_INTERVAL" \
    "--warmup-records=$WARMUP_RECORDS"
}

start_consumers() {
  for ((index = 1; index <= INSTANCES; index++)); do
    PERF_INSTANCE_ID="consumer-$index" \
    PERF_BATCH_SIZE="$BATCH_SIZE" \
    PERF_POLL_INTERVAL="$POLL_INTERVAL" \
    SERVER_PORT="$((9100 + index))" \
      run_java -jar "$CONSUMER_JAR" > "$LOG_DIR/consumer-$index.log" 2>&1 &
    echo "$!" > "$PID_DIR/consumer-$index.pid"
  done
  for ((index = 1; index <= INSTANCES; index++)); do
    wait_for_http "http://localhost:$((9100 + index))/actuator/health" "consumer-$index"
  done
}

REPORT_DIR="$ROOT_DIR/reports/$RUN_ID"
mkdir -p "$LOG_DIR" "$PID_DIR" "$REPORT_DIR/logs" "$(dirname "$TARGETS_FILE")"
rm -f "$PID_DIR"/*.pid "$LOG_DIR"/*.log
generate_prometheus_targets

echo "Starting PostgreSQL, Prometheus, Grafana and postgres-exporter..."
"${COMPOSE[@]}" up -d
wait_for_postgres
wait_for_http "http://localhost:$PROMETHEUS_PORT/-/healthy" "Prometheus"
apply_schema_and_reset

if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "Building consumer and tooling..."
  "${GRADLE[@]}" :consumer:bootJar :tooling:installDist
fi
[[ -x "$TOOL" && -f "$CONSUMER_JAR" ]] || { echo "Missing build outputs; run without --skip-build=true" >&2; exit 1; }
prepare_java_runtime

echo "Starting $INSTANCES consumer instance(s)..."
start_consumers
tool await-cluster "--instances=$INSTANCES" "--timeout=2m"

if (( WARMUP_RECORDS > 0 )); then
  WARMUP_RUN_ID="$RUN_ID-warmup"
  echo "Running warm-up with $WARMUP_RECORDS records..."
  seed "$WARMUP_RUN_ID" "$WARMUP_RECORDS"
  tool trigger "--run-id=$WARMUP_RUN_ID" "--timeout=$TRIGGER_TIMEOUT"
  tool await "--expected=$WARMUP_RECORDS" "--timeout=$RUN_TIMEOUT"
  tool reset-outbox
fi

REPORT_DIR="$ROOT_DIR/reports/$RUN_ID"
mkdir -p "$REPORT_DIR/logs"
if (( RETAINED_RECORDS > 0 )); then
  echo "Preloading $RETAINED_RECORDS completed records..."
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -v "retained_records=$RETAINED_RECORDS" -U username -d outbox_performance_test -f /sql/30_retained_records.sql
  if [[ "${PERF_SETTLE_RETAINED_RECORDS:-false}" == "true" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -c 'VACUUM (ANALYZE) outbox_record' -c 'CHECKPOINT'
  fi
fi
{
  printf 'Command:'
  printf ' %q' "$0" "$@"
  printf '\nPERF_VIRTUAL_THREADS=%s\n' "${PERF_VIRTUAL_THREADS:-false}"
  printf 'PERF_DRAIN_ON_FULL_BATCH=%s\n' "${PERF_DRAIN_ON_FULL_BATCH:-false}"
  printf 'PERF_PG_STAT_STATEMENTS=%s\n' "${PERF_PG_STAT_STATEMENTS:-false}"
  printf 'PERF_FULL_ROW_COMPLETION=%s\n' "${PERF_FULL_ROW_COMPLETION:-false}"
  for tuning_name in PERF_TUNING_REPOSITORY PERF_SQL_LIMIT PERF_HEAD_LIMIT_KEYS PERF_TUPLE_PREDECESSOR PERF_PG_READY_ORDER_INDEX PERF_PG_KEY_STATUS_INDEX PERF_INLINE_STATUS PERF_BULK_FETCH_SIZE PERF_MAX_RECORDS_PER_KEY PERF_LAZY_DESERIALIZATION PERF_AUTOCOMMIT_COMPLETION PERF_TRANSACTION_PER_KEY PERF_LIGHTWEIGHT_COLLECTOR PERF_COMPLETION_BATCH_SIZE PERF_COMPLETION_BATCH_WAIT_US PERF_COMPLETION_ARRAY_PARAMETERS PERF_PG_OUTBOX_FILLFACTOR PERF_NATIVE_COMPLETION_BATCHING PERF_NATIVE_COMPLETION_BATCH_SIZE PERF_NATIVE_COMPLETION_BATCH_WAIT PERF_NATIVE_RECORD_FETCH_BATCH_SIZE PERF_NATIVE_MAX_RECORDS_PER_KEY PERF_DURABLE_STOP_COUNTS PERF_JAVA_TOOL_OPTIONS PERF_CONSUMER_JAVA_TOOL_OPTIONS; do
    printf '%s=%s\n' "$tuning_name" "${!tuning_name:-unset}"
  done
  printf 'NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT=%s\n' "${NAMASTACK_OUTBOX_PROCESSING_EXECUTOR_CONCURRENCY_LIMIT:--1}"
  printf 'PERF_DB_POOL_SIZE=%s\n' "${PERF_DB_POOL_SIZE:-30}"
  printf 'PERF_SETTLE_RETAINED_RECORDS=%s\n' "${PERF_SETTLE_RETAINED_RECORDS:-false}"
  printf 'PERF_VERIFY_COMPLETION_ORDER=%s\n' "${PERF_VERIFY_COMPLETION_ORDER:-false}"
  printf 'Query and index options are recorded above; effective indexes are in PostgreSQL snapshots.\n'
  if [[ "$PERF_DB_URL" == *reWriteBatchedInserts=true* ]]; then
    printf 'JDBC batch insert rewriting=true\n'
  else
    printf 'JDBC batch insert rewriting=false\n'
  fi
  printf 'PERF_EXECUTOR_CORE_POOL_SIZE=%s\n' "${PERF_EXECUTOR_CORE_POOL_SIZE:-16}"
  printf 'PERF_EXECUTOR_MAX_POOL_SIZE=%s\n' "${PERF_EXECUTOR_MAX_POOL_SIZE:-32}"
  java -version 2>&1
  if [[ -n "$JAVA_CONTAINER_ID" ]]; then
    printf 'Java runtime location=Docker network\n'
    docker inspect --format 'Java runtime image={{.Config.Image}} image ID={{.Image}}' "$JAVA_CONTAINER_ID"
    docker exec "$JAVA_CONTAINER_ID" java -version 2>&1
  else
    printf 'Java runtime location=host\n'
  fi
  "${COMPOSE[@]}" images
  docker inspect --format 'PostgreSQL shared memory bytes: {{.HostConfig.ShmSize}}' "$("${COMPOSE[@]}" ps -q postgres)"
  docker info --format 'Docker CPUs={{.NCPU}} memory bytes={{.MemTotal}}'
  "${COMPOSE[@]}" exec -T postgres psql -X -qAt -U username -d outbox_performance_test -c "SELECT name || '=' || setting || COALESCE(' [' || unit || ']', '') FROM pg_settings WHERE name IN ('shared_buffers','effective_cache_size','work_mem','max_wal_size','min_wal_size','wal_buffers','wal_compression','checkpoint_timeout','checkpoint_completion_target','max_connections','jit','autovacuum_naptime','plan_cache_mode','enable_hashjoin','enable_mergejoin','fsync','synchronous_commit','full_page_writes','commit_delay','track_io_timing','track_wal_io_timing') ORDER BY name"
} > "$REPORT_DIR/execution.txt"
snapshot_postgres "$REPORT_DIR/postgres-before.json"
if [[ "${PERF_PG_STAT_STATEMENTS:-false}" == "true" ]]; then
  "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -c 'SELECT pg_stat_statements_reset()' > "$REPORT_DIR/statement-reset.txt"
fi
# Preserve independent ordering evidence until verification finishes. This does not relax durability
# or alter checkpoint thresholds; the physical slot only prevents recycling required WAL segments.
if [[ "${PERF_VERIFY_COMPLETION_ORDER:-false}" == "true" && "${PERF_RETAIN_VERIFICATION_WAL:-true}" == "true" ]]; then
  VERIFY_WAL_SLOT="namastack_perf_order_$$"
  "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U username -d outbox_performance_test \
    -c "SELECT pg_create_physical_replication_slot('$VERIFY_WAL_SLOT', true)"
  printf 'Verification WAL slot=%s (released at cleanup)\n' "$VERIFY_WAL_SLOT" >> "$REPORT_DIR/execution.txt"
fi
MEASUREMENT_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [[ "$MODE" == "steady-state" ]]; then
  echo "Starting steady-state collector for $RUN_ID..."
  tool collect-steady-state \
    "--run-id=$RUN_ID" \
    "--retained-completed-records=$RETAINED_RECORDS" \
    "--report-dir=$REPORT_DIR" \
    "--prometheus-url=http://localhost:$PROMETHEUS_PORT" \
    "--grafana-url=http://localhost:$GRAFANA_PORT/d/namastack-outbox-performance" \
    "--timeout=$RUN_TIMEOUT" \
    "--sample-interval=$SAMPLE_INTERVAL" > "$REPORT_DIR/collector.log" 2>&1 &
  COLLECTOR_PID=$!

  PRODUCER_ARGS=(
    "--run-id=$RUN_ID"
    "--profile=$PROFILE"
    "--producer-rate=$PRODUCER_RATE"
    "--producer-duration=$PRODUCER_DURATION"
    "--producer-batch-size=$PRODUCER_BATCH_SIZE"
    "--producer-workers=$PRODUCER_WORKERS"
    "--measurement-warmup=$MEASUREMENT_WARMUP"
    "--min-producer-rate-ratio=$MIN_PRODUCER_RATE_RATIO"
    "--records-per-key=$RECORDS_PER_KEY"
    "--instances=$INSTANCES"
    "--batch-size=$BATCH_SIZE"
    "--poll-interval=$POLL_INTERVAL"
    "--warmup-records=$WARMUP_RECORDS"
  )
  [[ -z "$MAX_BACKLOG_GROWTH_RATE" ]] || PRODUCER_ARGS+=("--max-backlog-growth-rate=$MAX_BACKLOG_GROWTH_RATE")
  [[ -z "$MAX_END_BACKLOG" ]] || PRODUCER_ARGS+=("--max-end-backlog=$MAX_END_BACKLOG")

  echo "Producing exactly rate × duration records: $PRODUCER_RATE records/s for $PRODUCER_DURATION..."
  tool produce "${PRODUCER_ARGS[@]}"
  snapshot_postgres "$REPORT_DIR/postgres-producer-stop.json"
  snapshot_statements "$REPORT_DIR/statements-producer-stop.json"
else
  echo "Preparing backlog-drain run $RUN_ID with $RECORDS records..."
  seed "$RUN_ID" "$RECORDS"
  tool collect-drain \
    "--run-id=$RUN_ID" \
    "--report-dir=$REPORT_DIR" \
    "--prometheus-url=http://localhost:$PROMETHEUS_PORT" \
    "--grafana-url=http://localhost:$GRAFANA_PORT/d/namastack-outbox-performance" \
    "--timeout=$RUN_TIMEOUT" \
    "--sample-interval=$SAMPLE_INTERVAL" > "$REPORT_DIR/collector.log" 2>&1 &
  COLLECTOR_PID=$!
  tool trigger "--run-id=$RUN_ID" "--timeout=$TRIGGER_TIMEOUT"
fi

set +e
wait "$COLLECTOR_PID"
COLLECTOR_EXIT=$?
set -e
COLLECTOR_PID=""
cp "$LOG_DIR"/*.log "$REPORT_DIR/logs/"
snapshot_postgres "$REPORT_DIR/postgres-after.json"
snapshot_statements "$REPORT_DIR/statements-after.json"
docker logs --timestamps --since "$MEASUREMENT_STARTED_AT" "$("${COMPOSE[@]}" ps -q postgres)" > "$REPORT_DIR/postgres.log" 2>&1
cat "$REPORT_DIR/collector.log"
(( COLLECTOR_EXIT == 0 )) || { echo "Performance run failed; see $REPORT_DIR" >&2; exit "$COLLECTOR_EXIT"; }
if grep -E '[[:space:]](WARN|ERROR)[[:space:]]' "$REPORT_DIR"/logs/*.log > "$REPORT_DIR/consumer-log-issues.log"; then
  echo "Consumer warnings/errors detected; this run does not establish healthy capacity. See $REPORT_DIR/consumer-log-issues.log" >&2
  exit 1
fi
if [[ "${PERF_VERIFY_COMPLETION_ORDER:-false}" == "true" ]]; then
  if ! "${COMPOSE[@]}" exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -v "run_id=$RUN_ID" -U username -d outbox_performance_test -f /sql/50_verify_completion_order.sql > "$REPORT_DIR/completion-order.json"; then
    # Clock timestamps can move backwards; only independent durable commit evidence can rescue such a result.
    python3 "$ROOT_DIR/scripts/verify-commit-order.py" "$("${COMPOSE[@]}" ps -q postgres)" "$RUN_ID" "$REPORT_DIR"
  fi
  cat "$REPORT_DIR/completion-order.json"
fi

echo
echo "Grafana dashboard: http://localhost:$GRAFANA_PORT/d/namastack-outbox-performance"
echo "Report: $REPORT_DIR/report.md"
