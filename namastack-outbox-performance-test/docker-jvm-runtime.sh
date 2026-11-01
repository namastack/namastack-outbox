#!/usr/bin/env bash
# Sourced by the runner. All benchmark JVMs share PostgreSQL's Docker network.
JAVA_CONTAINER_ID=""
JAVA_CONTAINER_NAME="namastack-perf-jvms-$$"

prepare_java_runtime() {
  local network env_name env_value
  local args=()
  network="$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}' "$("${COMPOSE[@]}" ps -q postgres)")"
  while IFS='=' read -r env_name env_value; do
    case "$env_name" in
      PERF_DB_URL) ;;
      PERF_*|NAMASTACK_OUTBOX_*) args+=(--env "$env_name") ;;
    esac
  done < <(env)
  for ((index = 1; index <= INSTANCES; index++)); do
    args+=(--publish "127.0.0.1:$((9100 + index)):$((9100 + index))")
  done
  JAVA_CONTAINER_ID="$(docker run --detach --init --name "$JAVA_CONTAINER_NAME" --network "$network" \
    --mount "type=bind,source=$ROOT_DIR,target=$ROOT_DIR,readonly" \
    --mount "type=bind,source=$ROOT_DIR/reports,target=$ROOT_DIR/reports" \
    --mount "type=bind,source=$RUNTIME_DIR,target=$RUNTIME_DIR" \
    --workdir "$ROOT_DIR" "${args[@]}" \
    --env 'PERF_DB_URL=jdbc:postgresql://postgres:5432/outbox_performance_test?reWriteBatchedInserts=true' \
    --env 'PERF_PROMETHEUS_QUERY_URL=http://prometheus:9090' \
    --env "PERF_GIT_COMMIT=$(git -C "$ROOT_DIR/.." rev-parse HEAD)" \
    --env "PERF_GIT_DIRTY=$([[ -n "$(git -C "$ROOT_DIR/.." status --porcelain)" ]] && echo true || echo false)" \
    --env "PERF_DOCKER_VERSION=$(docker version --format '{{.Server.Version}}')" \
    "$PERF_JAVA_IMAGE" sleep infinity)"
}

run_java() {
  exec docker exec --env "JAVA_TOOL_OPTIONS=$PERF_CONSUMER_JAVA_TOOL_OPTIONS" \
    --env PERF_INSTANCE_ID --env PERF_BATCH_SIZE --env PERF_POLL_INTERVAL --env SERVER_PORT \
    "$JAVA_CONTAINER_ID" java "$@"
}

run_tool() {
  docker exec --env "JAVA_TOOL_OPTIONS=$PERF_TOOL_JAVA_TOOL_OPTIONS" "$JAVA_CONTAINER_ID" "$TOOL" "$@"
}

cleanup_java_runtime() {
  if [[ -n "$JAVA_CONTAINER_ID" ]]; then
    docker rm --force "$JAVA_CONTAINER_ID" >/dev/null 2>&1 || true
  fi
}
