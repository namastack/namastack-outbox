#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml")
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
if (( INSTANCES < 1 || RECORDS < 1 || RECORDS_PER_KEY < 1 || WARMUP_RECORDS < 0 || PRODUCER_RATE < 1 || PRODUCER_BATCH_SIZE < 1 || PRODUCER_WORKERS < 1 )); then
  echo "Numeric counts must be positive; warmup-records may be zero" >&2
  exit 2
fi

COLLECTOR_PID=""

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
  "$TOOL" "$@"
}

generate_prometheus_targets() {
  {
    printf '[\n'
    for ((index = 1; index <= INSTANCES; index++)); do
      (( index == 1 )) || printf ',\n'
      printf '  {"targets":["host.docker.internal:%s"],"labels":{"consumer":"consumer-%s"}}' "$((9100 + index))" "$index"
    done
    printf '\n]\n'
  } > "$TARGETS_FILE"
}

apply_schema_and_reset() {
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/00_outbox_schema.sql
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/10_performance_schema.sql
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U username -d outbox_performance_test -f /sql/20_reset.sql
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
      java -jar "$CONSUMER_JAR" > "$LOG_DIR/consumer-$index.log" 2>&1 &
    echo "$!" > "$PID_DIR/consumer-$index.pid"
  done
  for ((index = 1; index <= INSTANCES; index++)); do
    wait_for_http "http://localhost:$((9100 + index))/actuator/health" "consumer-$index"
  done
}

mkdir -p "$LOG_DIR" "$PID_DIR" "$ROOT_DIR/reports" "$(dirname "$TARGETS_FILE")"
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

if [[ "$MODE" == "steady-state" ]]; then
  echo "Starting steady-state collector for $RUN_ID..."
  tool collect-steady-state \
    "--run-id=$RUN_ID" \
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
cat "$REPORT_DIR/collector.log"
(( COLLECTOR_EXIT == 0 )) || { echo "Performance run failed; see $REPORT_DIR" >&2; exit "$COLLECTOR_EXIT"; }

echo
echo "Grafana dashboard: http://localhost:$GRAFANA_PORT/d/namastack-outbox-performance"
echo "Report: $REPORT_DIR/report.md"
