#!/usr/bin/env bash
# Write cost per index set (fresh container each, extra DDL applied BEFORE seeding = incremental build),
# container outbox-db-H13. On B with H10 replace, also a diagnostic run of the original bulk query with
# enable_bitmapscan=off (ordered-path runtime when the planner does not pick it).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"
C=outbox-db-H13; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
run() { # S set ddl
  local S=$1 set=$2; shift 2
  docker rm -f -v $C >/dev/null 2>&1
  "$HS/start-postgres.sh" H13 "$S" "$@" >/dev/null || exit 1
  mkdir -p "$DB/results/G2-wc-$S"
  if [[ $set == H10 ]]; then
    "$HS/run-explain.sh" -c $C -s G2-wc-$S --tag nobitmap-$set --set enable_bitmapscan=off findIncompleteRecordPages
    "$HS/run-explain.sh" -c $C -s G2-wc-$S --tag $set findIncompleteRecordPages
  fi
  "$HS/write-cost.sh" $C -o "$DB/results/G2-wc-$S/$set.txt"
  docker rm -f -v $C >/dev/null
}
for S in "$@"; do
  run $S base
  run $S H10 --ddl "$DB/experiments/H10/indexes-replace.sql"
  run $S H10add --ddl "$DB/experiments/H10/indexes.sql"
done
