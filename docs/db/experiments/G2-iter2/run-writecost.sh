#!/usr/bin/env bash
# Write cost for U2b and H10 on top of H08a (fresh container each, pre-seed DDL). Compare with results/G1i2-wc-<S>/H08a.txt.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"
S="$1"; C=outbox-db-H15; OUT="$DB/results/G2i2-wc-$S"; mkdir -p "$OUT"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
for set in H08a U2b H10; do
  case $set in H08a) a=(--ddl "$DB/experiments/H08/indexes.sql");; U2b) a=(--ddl "$DB/experiments/H08/indexes.sql" --ddl "$D/indexes-u2b-on-h08a.sql");;
    H10) a=(--ddl "$DB/experiments/H08/indexes.sql" --ddl "$DB/experiments/H10/indexes.sql");; esac
  docker rm -f -v $C >/dev/null 2>&1; "$HS/start-postgres.sh" H15 "$S" "${a[@]}" >/dev/null || exit 1
  "$HS/write-cost.sh" $C -o "$OUT/$set.txt"; docker rm -f -v $C >/dev/null
done
