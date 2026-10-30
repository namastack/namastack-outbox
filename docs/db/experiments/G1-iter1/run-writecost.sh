#!/usr/bin/env bash
# Write cost per index set (fresh container each, extra indexes applied BEFORE seeding = incremental build).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"
C=outbox-db-H04
for S in "$@"; do for set in base H02 H03 H04; do
  docker rm -f -v $C >/dev/null 2>&1
  args=(); [[ $set == base ]] || args=(--ddl "$DB/experiments/$set/indexes.sql")
  "$HS/start-postgres.sh" H04 "$S" "${args[@]}" >/dev/null || exit 1
  mkdir -p "$DB/results/G1-wc-$S"
  "$HS/write-cost.sh" $C -o "$DB/results/G1-wc-$S/$set.txt"
  docker rm -f -v $C >/dev/null
done; done
