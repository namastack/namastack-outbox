#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATABASE=postgres
MODE=processing
RATE=20000
DURATION=1m
INSTANCES=4
HISTORY=1000000
RECORDS=80000
RECORDS_PER_KEY=10
BATCH_SIZE=1000
READERS=4
PRODUCER_WORKERS=16
PRODUCER_BATCH_SIZE=1000
STRICT=true
DELETE=true
SKIP_BUILD=false
KEEP_DATABASE=false
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

usage() {
  cat <<'HELP'
Usage: ./run-performance-test.sh [--option=value]

  --database=postgres         postgres, mysql, mariadb, sqlserver, oracle or h2
  --mode=processing           processing, read or producer
  --rate=20000                Target committed input records per second
  --duration=1m               Measurement duration: positive number plus ms, s, m or h
  --instances=4               Consumer JVMs (processing mode only)
  --history=1000000           Completed history records seeded before measurement
  --records=80000             Mixed-state fixture size (read mode only)
  --records-per-key=10        Records sharing an ordering key
  --batch-size=1000           Maximum keys per consumer poll/read query
  --readers=4                 Parallel readers (read mode only)
  --producer-workers=16       Parallel producer connections, at most 16
  --producer-batch-size=1000  Records per producer transaction; multiple of records-per-key
  --strict=true               Preserve strict per-key ordering
  --delete=true               Delete completed records instead of retaining them
  --skip-build=false          Reuse artifacts from a previous build of this checkout
  --keep-database=false       Retain this run's database for inspection
  --run-id=<id>               Unique report-directory name
  --help                     Show this help

Requires Docker Compose, Bash and Git. Builds and JVMs run in Docker.
Each run creates a disposable database using the library's actual schema.
HELP
}

fail() { echo "$*" >&2; exit 2; }
for argument in "$@"; do
  case "$argument" in
    --database=*) DATABASE="${argument#*=}" ;;
    --mode=*) MODE="${argument#*=}" ;;
    --rate=*) RATE="${argument#*=}" ;;
    --duration=*) DURATION="${argument#*=}" ;;
    --instances=*) INSTANCES="${argument#*=}" ;;
    --history=*) HISTORY="${argument#*=}" ;;
    --records=*) RECORDS="${argument#*=}" ;;
    --records-per-key=*) RECORDS_PER_KEY="${argument#*=}" ;;
    --batch-size=*) BATCH_SIZE="${argument#*=}" ;;
    --readers=*) READERS="${argument#*=}" ;;
    --producer-workers=*) PRODUCER_WORKERS="${argument#*=}" ;;
    --producer-batch-size=*) PRODUCER_BATCH_SIZE="${argument#*=}" ;;
    --strict=*) STRICT="${argument#*=}" ;;
    --delete=*) DELETE="${argument#*=}" ;;
    --skip-build=*) SKIP_BUILD="${argument#*=}" ;;
    --keep-database=*) KEEP_DATABASE="${argument#*=}" ;;
    --run-id=*) RUN_ID="${argument#*=}" ;;
    --help) usage; exit 0 ;;
    *) fail "Unknown option: $argument. Use --help." ;;
  esac
done
case "$DATABASE" in postgres|mysql|mariadb|sqlserver|oracle|h2) ;; *) fail 'Unsupported database. Use --help.' ;; esac
case "$MODE" in processing|read|producer) ;; *) fail 'Expected processing, read or producer mode.' ;; esac
for value in "$STRICT" "$DELETE" "$SKIP_BUILD" "$KEEP_DATABASE"; do
  [[ "$value" == true || "$value" == false ]] || fail 'Boolean options must be true or false.'
done
for value in "$RATE" "$INSTANCES" "$RECORDS" "$RECORDS_PER_KEY" "$BATCH_SIZE" "$READERS" "$PRODUCER_WORKERS" "$PRODUCER_BATCH_SIZE"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail 'Counts must be positive decimal integers.'
done
[[ "$HISTORY" =~ ^(0|[1-9][0-9]*)$ ]] || fail 'History must be a non-negative decimal integer.'
[[ "$DURATION" =~ ^[1-9][0-9]*(ms|s|m|h)$ ]] || fail 'Duration must be positive, for example 30s or 5m.'
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || fail 'Invalid run ID.'
(( INSTANCES <= 256 && READERS <= 256 )) || fail 'At most 256 consumers/readers are supported.'
(( PRODUCER_WORKERS <= 16 )) || fail 'At most 16 producer workers are supported.'
(( PRODUCER_BATCH_SIZE % RECORDS_PER_KEY == 0 )) || fail 'Producer batch size must be a multiple of records per key.'
[[ "$MODE" == processing ]] || INSTANCES=0
if [[ "$MODE" == read ]]; then
  (( RECORDS % RECORDS_PER_KEY == 0 )) || fail 'Read fixture size must be a multiple of records per key.'
fi
for command in docker git; do command -v "$command" >/dev/null || fail "Install $command first."; done
docker compose version >/dev/null || fail 'Docker Compose v2 or later is required.'
docker info >/dev/null 2>&1 || fail 'Start Docker before running the benchmark.'

REPORT_DIR="$ROOT_DIR/reports/$RUN_ID"
[[ ! -e "$REPORT_DIR" ]] || fail "Report directory already exists: $REPORT_DIR"
# Serialize workloads across checkouts so CPU, memory and disk measurements do not overlap.
RUN_LOCK="${TMPDIR:-/tmp}/namastack-jdbc-performance-test.lock"
mkdir "$RUN_LOCK" 2>/dev/null || fail "Another performance run holds $RUN_LOCK."
PROJECT_NAME="namastack-outbox-perf-$(date -u +%Y%m%d%H%M%S)-$$"
COMPOSE=(docker compose -p "$PROJECT_NAME" -f "$ROOT_DIR/docker-compose.yml" --profile "$DATABASE" --profile benchmark)
JAVA_CONTAINER="${PROJECT_NAME}-jvms"
STARTED=false
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  docker rm --force "$JAVA_CONTAINER" >/dev/null 2>&1 || true
  if [[ "$KEEP_DATABASE" == true && "$STARTED" == true ]]; then
    echo "Database retained. Remove it with:"
    echo "docker compose -p $PROJECT_NAME -f $ROOT_DIR/docker-compose.yml --profile $DATABASE down --volumes --remove-orphans"
  else
    "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rmdir "$RUN_LOCK" 2>/dev/null || true
  if (( code != 0 )); then echo "Run failed. Diagnostics: $REPORT_DIR" >&2; fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$REPORT_DIR"
printf '%s\n' "$PROJECT_NAME" > "$REPORT_DIR/project.txt"
export PERF_UID="$(id -u)" PERF_GID="$(id -g)"
export PERF_GIT_COMMIT="$(git -C "$ROOT_DIR/.." rev-parse HEAD)"
export PERF_GIT_DIRTY="$([[ -n "$(git -C "$ROOT_DIR/.." status --porcelain)" ]] && echo true || echo false)"
git -C "$ROOT_DIR/.." status --short > "$REPORT_DIR/source-status.txt"
git -C "$ROOT_DIR/.." diff HEAD --binary > "$REPORT_DIR/source.patch"
export PERF_BATCH_SIZE="$BATCH_SIZE" PERF_STRICT_ORDERING="$STRICT" PERF_DELETE_COMPLETED="$DELETE"
export PERF_CONSUMER_JAVA_OPTIONS="${PERF_CONSUMER_JAVA_OPTIONS:--Xms128m -Xmx512m -XX:ActiveProcessorCount=2}"
export PERF_TOOL_JAVA_OPTIONS="${PERF_TOOL_JAVA_OPTIONS:--Xms128m -Xmx1g}"
export PERF_DB_USER=username PERF_DB_PASSWORD=password
case "$DATABASE" in
  postgres) export PERF_INTERNAL_DB_URL='jdbc:postgresql://postgres:5432/outbox_performance_test?reWriteBatchedInserts=true' ;;
  mysql) export PERF_INTERNAL_DB_URL='jdbc:mysql://mysql:3306/outbox_performance_test?rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false' ;;
  mariadb) export PERF_INTERNAL_DB_URL='jdbc:mariadb://mariadb:3306/outbox_performance_test' ;;
  sqlserver)
    export PERF_DB_USER=sa PERF_DB_PASSWORD='Benchmark-Password-42!'
    export PERF_INTERNAL_DB_URL='jdbc:sqlserver://sqlserver:1433;databaseName=outbox_performance_test;encrypt=false;useBulkCopyForBatchInsert=true;sendTemporalDataTypesAsStringForBulkCopy=false;sendStringParametersAsUnicode=false' ;;
  oracle) export PERF_INTERNAL_DB_URL='jdbc:oracle:thin:@//oracle:1521/FREEPDB1?defaultRowPrefetch=1000&oracle.jdbc.defaultLobPrefetchSize=512' ;;
  h2) export PERF_INTERNAL_DB_URL='jdbc:h2:tcp://h2:9092//data/outbox;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1' ;;
esac
if [[ "$SKIP_BUILD" == false ]]; then
  echo 'Building the current checkout in Docker (first run downloads JVMs and Gradle dependencies)...'
  docker volume create namastack-outbox-perf-gradle-cache >/dev/null
  if ! "${COMPOSE[@]}" --profile build build build > "$REPORT_DIR/build.log" 2>&1; then tail -50 "$REPORT_DIR/build.log" >&2; exit 1; fi
  if ! "${COMPOSE[@]}" --profile build run --rm --no-deps build ../gradlew --no-daemon --no-configuration-cache \
    -Dorg.gradle.java.installations.paths=/opt/java/jdk17,/opt/java/openjdk -Dorg.gradle.jvmargs=-Xmx2g \
    -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
    :tooling:installDist :consumer:bootJar :tooling:test >> "$REPORT_DIR/build.log" 2>&1; then tail -60 "$REPORT_DIR/build.log" >&2; exit 1; fi
fi
[[ -x "$ROOT_DIR/tooling/build/install/tooling/bin/tooling" && -f "$ROOT_DIR/consumer/build/libs/namastack-outbox-performance-test-consumer.jar" ]] || fail 'Build artifacts are missing. Run without --skip-build.'

echo "Starting $DATABASE; reports: $REPORT_DIR"
"${COMPOSE[@]}" up -d --wait --wait-timeout 600 "$DATABASE" > "$REPORT_DIR/startup.log" 2>&1 || { tail -50 "$REPORT_DIR/startup.log" >&2; exit 1; }
STARTED=true
"${COMPOSE[@]}" run --detach --no-deps --name "$JAVA_CONTAINER" benchmark >/dev/null
TOOL=/benchmark/tooling/build/install/tooling/bin/tooling
arguments=(jdbc-benchmark "--database=$DATABASE" "--report-dir=/benchmark/reports/$RUN_ID")
run_tool() { docker exec --env "JAVA_TOOL_OPTIONS=${PERF_TOOL_JAVA_OPTIONS:--Xms128m -Xmx1g}" "$JAVA_CONTAINER" "$TOOL" "${arguments[@]}" "$@"; }
"${COMPOSE[@]}" config > "$REPORT_DIR/compose.yml"
docker info --format 'CPUs={{.NCPU}} memory={{.MemTotal}} version={{.ServerVersion}}' > "$REPORT_DIR/docker.txt"
docker inspect "$("${COMPOSE[@]}" ps -q "$DATABASE")" --format '{{.Config.Image}} {{.Image}} {{json .Config.Cmd}}' >> "$REPORT_DIR/docker.txt"
run_tool --action=environment "--mode=$MODE" "--duration=$DURATION" "--rate=$RATE" "--instances=$INSTANCES" \
  "--history=$HISTORY" "--records=$RECORDS" "--records-per-key=$RECORDS_PER_KEY" "--batch-size=$BATCH_SIZE" \
  "--readers=$READERS" "--producer-workers=$PRODUCER_WORKERS" "--producer-batch-size=$PRODUCER_BATCH_SIZE" \
  "--strict=$STRICT" "--delete=$DELETE" > "$REPORT_DIR/environment.log" 2>&1
run_tool --action=initialize > "$REPORT_DIR/initialize.log" 2>&1
fixture_records=0
[[ "$MODE" != read ]] || fixture_records="$RECORDS"
echo "Seeding $HISTORY completed history records..."
run_tool --action=seed "--history=$HISTORY" "--records=$fixture_records" "--records-per-key=$RECORDS_PER_KEY" \
  "--run-id=$RUN_ID" "--producer-workers=$PRODUCER_WORKERS" "--producer-batch-size=$PRODUCER_BATCH_SIZE" > "$REPORT_DIR/seed.log" 2>&1
if [[ "$MODE" == processing ]]; then
  for ((index = 1; index <= INSTANCES; index++)); do
    docker exec --env "JAVA_TOOL_OPTIONS=${PERF_CONSUMER_JAVA_OPTIONS:--Xms128m -Xmx512m -XX:ActiveProcessorCount=2}" \
      --env "PERF_INSTANCE_ID=consumer-$index" "$JAVA_CONTAINER" \
      java -jar /benchmark/consumer/build/libs/namastack-outbox-performance-test-consumer.jar > "$REPORT_DIR/consumer-$index.log" 2>&1 &
    run_tool --action=await-cluster "--instances=$index" >> "$REPORT_DIR/cluster.log" 2>&1
  done
  echo 'Warming up the consumer cluster...'
  run_tool --action=produce "--run-id=$RUN_ID-warmup" --duration=3s "--rate=$(( RATE < 20000 ? RATE : 20000 ))" \
    "--records-per-key=$RECORDS_PER_KEY" "--producer-workers=$PRODUCER_WORKERS" "--producer-batch-size=$PRODUCER_BATCH_SIZE" \
    "--delete=$DELETE" --label=warmup > "$REPORT_DIR/warmup.log" 2>&1
  mv "$REPORT_DIR/summary.md" "$REPORT_DIR/warmup-summary.md"
  run_tool --action=await-drain "--run-id=$RUN_ID-warmup" >> "$REPORT_DIR/warmup.log" 2>&1
fi
echo "Measuring $MODE for $DURATION..."
case "$MODE" in
  processing|producer)
    run_tool --action=produce "--run-id=$RUN_ID" "--duration=$DURATION" "--rate=$RATE" \
      "--records-per-key=$RECORDS_PER_KEY" "--producer-workers=$PRODUCER_WORKERS" "--producer-batch-size=$PRODUCER_BATCH_SIZE" \
      "--delete=$DELETE" "--producer-only=$([[ "$MODE" == producer ]] && echo true || echo false)" > "$REPORT_DIR/measurement.log" 2>&1 ;;
  read)
    run_tool --action=read "--duration=$DURATION" "--readers=$READERS" "--batch-size=$BATCH_SIZE" "--strict=$STRICT" > "$REPORT_DIR/measurement.log" 2>&1 ;;
esac
cat "$REPORT_DIR/summary.md"
echo "Reports: $REPORT_DIR"
