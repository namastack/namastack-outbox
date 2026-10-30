#!/usr/bin/env bash
# Write cost per index set (fresh container each, pre-seed DDL = incremental build). Usage: run-writecost.sh <S> <container-suffix>
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"
S="$1"; N="$2"; C=outbox-db-$N; OUT="$DB/results/G1i2-wc-$S"; mkdir -p "$OUT"
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
ddl() { case $1 in H04) echo "$DB/experiments/H04/indexes.sql";; H06) echo "$DB/experiments/H06/indexes.sql";; H08a) echo "$DB/experiments/H08/indexes.sql";;
  H08b) echo "$DB/experiments/H08/indexes-b.sql";; H07a) echo "$DB/experiments/H07/indexes.sql";; H07b) echo "$DB/experiments/H07/indexes-covering.sql";;
  H09a) echo "$DB/experiments/H09/indexes-a.sql";; H09b) echo "$DB/experiments/H09/indexes-b.sql";; H09n) echo "$DB/experiments/H09/indexes-n-h10.sql";; H09o) echo "$DB/experiments/H09/indexes-n-h10-outer.sql";; esac; }
for set in base H04 H06 H08a H08b H07a H07b H09a H09b H09n H09o; do
  docker rm -f -v $C >/dev/null 2>&1
  args=(); [[ $set == base ]] || args=(--ddl "$(ddl $set)")
  "$HS/start-postgres.sh" "$N" "$S" "${args[@]}" >/dev/null || exit 1
  "$HS/write-cost.sh" $C -o "$OUT/$set.txt"
  docker rm -f -v $C >/dev/null
done
