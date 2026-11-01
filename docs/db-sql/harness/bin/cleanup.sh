#!/usr/bin/env bash
# Stop all harness containers; with --wipe also delete their containers and data volumes (and h2 files).
H="$(cd "$(dirname "$0")/.." && pwd)"
for o in $(docker ps -a --format '{{.Names}}' | grep '^outbox-bench-' || true); do
  docker rm -f "$o" >/dev/null && echo "removed container $o"
done
if [ "${1:-}" = "--wipe" ]; then
  for v in $(docker volume ls --format '{{.Name}}' | grep '^outbox-bench-' || true); do docker volume rm "$v" >/dev/null && echo "removed volume $v"; done
  rm -rf "$H/build/h2" && echo "removed h2 data"
fi
df -h / | tail -1
