#!/usr/bin/env bash
# Sourced by the runner; keeps Java clients beside PostgreSQL when PERF_JAVA_IMAGE is set.
JAVA_CONTAINER_ID=""
JAVA_CONTAINER_NAME="namastack-perf-jvms-$$"

prepare_java_runtime() {
  [[ -n "${PERF_JAVA_IMAGE:-}" ]] || return 0
  local network env_name env_value db_url
  local container_args=()
  network="$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}' "$("${COMPOSE[@]}" ps -q postgres)")"
  while IFS='=' read -r env_name env_value; do
    case "$env_name" in
      PERF_DB_URL) ;;
      PERF_*|NAMASTACK_OUTBOX_*) container_args+=(--env "$env_name") ;;
    esac
  done < <(env)
  db_url="jdbc:postgresql://postgres:5432/outbox_performance_test"
  if [[ "$PERF_DB_URL" == *'?'* ]]; then
    db_url+="?${PERF_DB_URL#*\?}"
  fi
  for ((index = 1; index <= INSTANCES; index++)); do
    container_args+=(--publish "127.0.0.1:$((9100 + index)):$((9100 + index))")
  done
  JAVA_CONTAINER_ID="$(docker run --detach --name "$JAVA_CONTAINER_NAME" --network "$network" \
    --mount "type=bind,source=$ROOT_DIR,target=$ROOT_DIR,readonly" \
    --mount "type=bind,source=$ROOT_DIR/reports,target=$ROOT_DIR/reports" \
    --mount "type=bind,source=$RUNTIME_DIR,target=$RUNTIME_DIR" \
    --workdir "$ROOT_DIR" "${container_args[@]}" \
    --env "PERF_DB_URL=${PERF_DOCKER_DB_URL:-$db_url}" \
    --env 'PERF_PROMETHEUS_QUERY_URL=http://prometheus:9090' \
    --env "PERF_GIT_COMMIT=$(git -C "$ROOT_DIR/.." rev-parse HEAD)" \
    --env "PERF_GIT_DIRTY=$([[ -n "$(git -C "$ROOT_DIR/.." status --porcelain)" ]] && echo true || echo false)" \
    --env "PERF_DOCKER_VERSION=$(docker version --format '{{.Server.Version}}')" \
    "$PERF_JAVA_IMAGE" sleep infinity)"
  docker exec "$JAVA_CONTAINER_ID" java -version
}

run_java() {
  if [[ -n "$JAVA_CONTAINER_ID" ]]; then
    if [[ -n "${PERF_CONSUMER_JAVA_TOOL_OPTIONS:-}" ]]; then
      exec docker exec --env "JAVA_TOOL_OPTIONS=$PERF_CONSUMER_JAVA_TOOL_OPTIONS" \
        --env PERF_INSTANCE_ID --env PERF_BATCH_SIZE --env PERF_POLL_INTERVAL --env SERVER_PORT \
        "$JAVA_CONTAINER_ID" java "$@"
    else
      exec docker exec --env PERF_INSTANCE_ID --env PERF_BATCH_SIZE --env PERF_POLL_INTERVAL --env SERVER_PORT \
        "$JAVA_CONTAINER_ID" java "$@"
    fi
  else
    if [[ -n "${PERF_CONSUMER_JAVA_TOOL_OPTIONS:-}" ]]; then
      export JAVA_TOOL_OPTIONS="$PERF_CONSUMER_JAVA_TOOL_OPTIONS"
    fi
    exec java "$@"
  fi
}

run_tool() {
  if [[ -n "$JAVA_CONTAINER_ID" ]]; then
    docker exec "$JAVA_CONTAINER_ID" "$TOOL" "$@"
  else
    "$TOOL" "$@"
  fi
}

cleanup_java_runtime() {
  if [[ -n "$JAVA_CONTAINER_ID" ]]; then
    docker rm --force "$JAVA_CONTAINER_ID" >/dev/null 2>&1 || true
  fi
}
