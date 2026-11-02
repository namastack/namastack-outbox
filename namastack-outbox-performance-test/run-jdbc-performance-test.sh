#!/usr/bin/env bash
# Portable, repeatable read-path benchmark against the library's actual JDBC repository.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATABASE=mysql
MODE=read
RATE=20000
INSTANCES=8
DELETE=false
DURATION=20s
READERS=8
HISTORY=1000000
RECORDS=256000
BATCH_SIZE=2000
STRICT=true
DECODE=true
SKIP_BUILD=false
REUSE=false
KEEP_DATABASE=false
LABEL=current
RUN_ID="jdbc-$(date -u +%Y%m%dT%H%M%SZ)-$$"
for argument in "$@"; do
  case "$argument" in
    --mode=*) MODE="${argument#*=}" ;;
    --rate=*) RATE="${argument#*=}" ;;
    --instances=*) INSTANCES="${argument#*=}" ;;
    --delete=*) DELETE="${argument#*=}" ;;
    --database=*) DATABASE="${argument#*=}" ;;
    --duration=*) DURATION="${argument#*=}" ;;
    --readers=*) READERS="${argument#*=}" ;;
    --history=*) HISTORY="${argument#*=}" ;;
    --records=*) RECORDS="${argument#*=}" ;;
    --batch-size=*) BATCH_SIZE="${argument#*=}" ;;
    --strict=*) STRICT="${argument#*=}" ;;
    --decode=*) DECODE="${argument#*=}" ;;
    --skip-build=*) SKIP_BUILD="${argument#*=}" ;;
    --keep-database=*) KEEP_DATABASE="${argument#*=}" ;;
    --reuse=*) REUSE="${argument#*=}" ;;
    --run-id=*) RUN_ID="${argument#*=}" ;;
    --label=*) LABEL="${argument#*=}" ;;
    --help)
      echo 'Usage: ./run-jdbc-performance-test.sh --database=mysql [--duration=20s] [--readers=8]'
      echo 'Use --mode=processing --rate=150000 --duration=1m --delete=true for a real scheduler throughput screen.'
      echo 'Databases: postgres, mysql, sqlserver, oracle, mariadb, h2.'
      echo 'The selected dedicated database is reset and seeded. Reports distinguish repeated reads from completed processing.'
      echo 'Use --reuse=true --run-id=<previous-id> --label=<variant> to compare against the same fixture.'
      exit 0 ;;
    *) echo "Unknown argument: $argument" >&2; exit 2 ;;
  esac
done
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo 'Invalid run-id' >&2; exit 2; }
[[ "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo 'Invalid label' >&2; exit 2; }
[[ "$MODE" == read || "$MODE" == processing ]] || { echo 'Expected read or processing mode' >&2; exit 2; }
for value in "$STRICT" "$DECODE" "$SKIP_BUILD" "$REUSE" "$DELETE" "$KEEP_DATABASE"; do
  [[ "$value" == true || "$value" == false ]] || { echo 'Expected true or false' >&2; exit 2; }
done
for value in "$READERS" "$RECORDS" "$BATCH_SIZE" "$RATE" "$INSTANCES"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo 'Positive integer required' >&2; exit 2; }
done
[[ "$HISTORY" =~ ^[0-9]+$ ]] || { echo 'Non-negative history required' >&2; exit 2; }
(( READERS <= 256 )) || { echo 'At most 256 partition readers supported' >&2; exit 2; }
export PERF_DB_USER=username PERF_DB_PASSWORD=password
case "$DATABASE" in
  postgres) export PERF_INTERNAL_DB_URL='jdbc:postgresql://postgres:5432/outbox_performance_test?reWriteBatchedInserts=true' ;;
  mysql) export PERF_INTERNAL_DB_URL='jdbc:mysql://mysql:3306/outbox_performance_test?rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false' ;;
  sqlserver)
    export PERF_DB_USER=sa PERF_DB_PASSWORD='Benchmark-Password-42!'
    export PERF_INTERNAL_DB_URL="jdbc:sqlserver://sqlserver:1433;databaseName=outbox_performance_test;encrypt=false;useBulkCopyForBatchInsert=true;sendTemporalDataTypesAsStringForBulkCopy=false;sendStringParametersAsUnicode=${PERF_SQLSERVER_UNICODE_PARAMETERS:-true};queryTimeout=60;cancelQueryTimeout=5" ;;
  oracle) export PERF_INTERNAL_DB_URL="jdbc:oracle:thin:@//oracle:1521/FREEPDB1${PERF_ORACLE_JDBC_URL_SUFFIX-?defaultRowPrefetch=1000&oracle.jdbc.defaultLobPrefetchSize=512}" ;;
  mariadb) export PERF_INTERNAL_DB_URL='jdbc:mariadb://mariadb:3306/outbox_performance_test' ;;
  h2) export PERF_INTERNAL_DB_URL='jdbc:h2:tcp://h2:9092//data/outbox;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1;CACHE_SIZE=2097152' ;;
  *) echo 'Unsupported database' >&2; exit 2 ;;
esac
export PERF_DATABASE_SERVICE="$DATABASE"
export PERF_JAVA_IMAGE="${PERF_JAVA_IMAGE:-eclipse-temurin:21-jre}"
export PERF_TOOL_JAVA_TOOL_OPTIONS="${PERF_TOOL_JAVA_TOOL_OPTIONS:--Xms256m -Xmx2g}"
export PERF_CONSUMER_JAVA_TOOL_OPTIONS="${PERF_CONSUMER_JAVA_TOOL_OPTIONS:--Xms128m -Xmx768m}"
[[ "$MODE" == read ]] && INSTANCES=0
export PERF_STRICT_ORDERING="$STRICT"
export NAMASTACK_OUTBOX_PROCESSING_DELETECOMPLETEDRECORDS="$DELETE"
RUNTIME_DIR="$ROOT_DIR/runtime/jdbc"
REPORT_DIR="$ROOT_DIR/reports/$RUN_ID"
TOOL="$ROOT_DIR/tooling/build/install/tooling/bin/tooling"
COMPOSE=(docker compose -p namastack-jdbc-performance-test -f "$ROOT_DIR/docker-compose-jdbc.yml" --profile "$DATABASE")
mkdir -p "$RUNTIME_DIR" "$REPORT_DIR"
source "$ROOT_DIR/docker-jvm-runtime.sh"
# Worktrees share the same Docker project and volumes, so the run lock must be shared too.
JDBC_RUN_LOCK="${TMPDIR:-/tmp}/namastack-jdbc-performance-test.lock"
mkdir "$JDBC_RUN_LOCK" 2>/dev/null || { echo "Another JDBC benchmark holds $JDBC_RUN_LOCK" >&2; exit 1; }
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  cleanup_java_runtime
  if [[ "$KEEP_DATABASE" == false ]]; then "${COMPOSE[@]}" stop "$DATABASE" >/dev/null 2>&1 || true; fi
  rmdir "$JDBC_RUN_LOCK" 2>/dev/null || true
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
if [[ "$SKIP_BUILD" == false ]]; then
  "$ROOT_DIR/../gradlew" --no-configuration-cache -p "$ROOT_DIR" :tooling:installDist :consumer:bootJar > "$REPORT_DIR/build.log" 2>&1
fi
# Only one selected database runs, leaving the Docker VM's resources available for it.
if [[ "$DATABASE" != h2 ]]; then
  "${COMPOSE[@]}" up -d --wait --wait-timeout 600 "$DATABASE"
else
  "${COMPOSE[@]}" up -d "$DATABASE"
  sleep 2
fi
prepare_java_runtime
{
  git -C "$ROOT_DIR/.." rev-parse HEAD
  git -C "$ROOT_DIR/.." status --short
  docker info --format 'Docker CPUs={{.NCPU}} memory={{.MemTotal}}'
  docker inspect "$JAVA_CONTAINER_ID" --format 'JVM image={{.Config.Image}} id={{.Image}}'
  docker exec "$JAVA_CONTAINER_ID" java -version 2>&1
  docker inspect "$("${COMPOSE[@]}" ps -q "$DATABASE")" --format 'Database image={{.Config.Image}} id={{.Image}}'
  python3 - "$ROOT_DIR" <<'PYHASH'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
paths = list((root / 'tooling/build/install/tooling/lib').glob('namastack-outbox-jdbc-*.jar'))
paths += list((root / 'consumer/build/libs').glob('*.jar'))
for path in paths:
    print(hashlib.sha256(path.read_bytes()).hexdigest(), path.name)
PYHASH
} > "$REPORT_DIR/execution-$LABEL.txt"
python3 - "$REPORT_DIR/parameters-$LABEL.json" "$DATABASE" "$MODE" "$DURATION" "$RATE" "$INSTANCES" "$READERS" "$HISTORY" "$RECORDS" "$BATCH_SIZE" "$STRICT" "$DELETE" "$DECODE" <<'PYPARAMETERS'
import json, os, pathlib, sys
names = ('database', 'mode', 'duration', 'rate', 'instances', 'readers', 'history', 'records', 'batchSize', 'strict', 'delete', 'decode')
settings = dict(zip(names, sys.argv[2:]))
allowed = (
    'PERF_DB_POOL_SIZE', 'PERF_DB_MIN_IDLE', 'PERF_VIRTUAL_THREADS',
    'PERF_EXECUTOR_CONCURRENCY_LIMIT', 'PERF_EXECUTOR_CORE_POOL_SIZE', 'PERF_EXECUTOR_MAX_POOL_SIZE',
    'PERF_COMPLETION_BATCHING', 'PERF_BULK_COMPLETION_UPDATES', 'PERF_COMPLETION_BATCH_SIZE',
    'PERF_COMPLETION_BATCH_WAIT', 'PERF_COMPLETION_WRITERS', 'PERF_SQLSERVER_UNICODE_PARAMETERS',
    'PERF_PG_SHARED_BUFFERS', 'PERF_PG_EFFECTIVE_CACHE_SIZE',
    'PERF_TOOL_JAVA_TOOL_OPTIONS', 'PERF_CONSUMER_JAVA_TOOL_OPTIONS',
)
settings['environmentOverrides'] = {name: os.environ[name] for name in allowed if name in os.environ}
settings['consumerDefaults'] = 'consumer/src/main/resources/application.yml in the recorded checkout'
pathlib.Path(sys.argv[1]).write_text(json.dumps(settings, indent=2) + '\n')
PYPARAMETERS
arguments=(jdbc-benchmark "--database=$DATABASE" "--report-dir=$REPORT_DIR")
if [[ "$REUSE" == true ]]; then
  run_tool "${arguments[@]}" --action=upgrade > "$REPORT_DIR/upgrade-$LABEL.log" 2>&1
else
  run_tool "${arguments[@]}" --action=initialize > "$REPORT_DIR/initialize.log" 2>&1
  run_tool "${arguments[@]}" --action=seed "--history=$HISTORY" "--records=$([[ "$MODE" == read ]] && echo "$RECORDS" || echo 0)" > "$REPORT_DIR/seed.log" 2>&1
fi
if [[ "$MODE" == processing ]]; then
  CONSUMER_JAR="$ROOT_DIR/consumer/build/libs/namastack-outbox-performance-test-consumer.jar"
  for ((index = 1; index <= INSTANCES; index++)); do
    PERF_INSTANCE_ID="consumer-$index" PERF_BATCH_SIZE="$BATCH_SIZE" PERF_POLL_INTERVAL=10ms SERVER_PORT="$((9100 + index))" \
      run_java -jar "$CONSUMER_JAR" > "$REPORT_DIR/consumer-$index.log" 2>&1 &
  done
  for ((index = 1; index <= INSTANCES; index++)); do
    ready=false
    for ((attempt = 1; attempt <= 120; attempt++)); do
      if curl --fail --silent "http://localhost:$((9100 + index))/actuator/health" >/dev/null; then ready=true; break; fi
      sleep 1
    done
    [[ "$ready" == true ]] || { echo "Consumer $index did not become healthy" >&2; exit 1; }
  done
  run_tool "${arguments[@]}" --action=await-cluster "--instances=$INSTANCES" > "$REPORT_DIR/cluster.log" 2>&1
  run_tool "${arguments[@]}" --action=produce "--run-id=$RUN_ID-warmup" --duration=3s "--rate=$(( RATE < 20000 ? RATE : 20000 ))" "--delete=$DELETE" --label=warmup > "$REPORT_DIR/warmup.log" 2>&1
  run_tool "${arguments[@]}" --action=await-drain "--run-id=$RUN_ID-warmup" >> "$REPORT_DIR/warmup.log" 2>&1
  run_tool "${arguments[@]}" --action=produce "--run-id=$RUN_ID" "--duration=$DURATION" "--rate=$RATE" "--delete=$DELETE" "--label=$LABEL" 2>&1 | tee "$REPORT_DIR/processing-$LABEL.log"
else
  run_tool "${arguments[@]}" --action=read "--duration=$DURATION" "--readers=$READERS" "--batch-size=$BATCH_SIZE" "--strict=$STRICT" "--decode=$DECODE" "--label=$LABEL" 2>&1 | tee "$REPORT_DIR/read-$LABEL.log"
fi
echo "Reports: $REPORT_DIR"
