#!/usr/bin/env bash
# Edge-seed equivalence for every iteration-2 state, key_ties 0/1, custom + generic. -> results/G1i2-edge/equivalence.txt
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"
OUT="$DB/results/G1i2-edge"; mkdir -p "$OUT"; : > "$OUT/equivalence.txt"
C=outbox-db-H09; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
source "$D/states.sh"
for ties in 0 1; do
  docker rm -f -v $C >/dev/null 2>&1
  "$HS/start-postgres.sh" H09 none >/dev/null
  docker exec -i $C psql -U postgres -X -q -v key_ties=$ties < "$HS/seed-edge.sql"; docker exec $C psql -U postgres -qc "VACUUM ANALYZE"
  for st in "H04 H05" "H06 H06" "H08 H08" "H07a H07" "H09a H09a" "H09b H09b" "H09n_base H09na" "H09n_base H09nb" "H09n_h10 H09na" "H09n_h10 H09nb"; do
    set -- $st; state_$1
    for g in 0 1; do echo "## state=$1 qdir=$2 key_ties=$ties generic=$g" >> "$OUT/equivalence.txt"; "$D/eq-suite.sh" $C $2 generic=$g >> "$OUT/equivalence.txt" 2>&1; done
  done
done
echo "PASS: $(grep -c '^PASS ' "$OUT/equivalence.txt") PASS-TIES: $(grep -c '^PASS-TIES' "$OUT/equivalence.txt") FAIL: $(grep -c '^FAIL' "$OUT/equivalence.txt")"
