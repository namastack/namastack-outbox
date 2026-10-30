#!/usr/bin/env bash
# Equivalence on the edge seed (with and without key_ties=1), every index state, custom + generic.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"
OUT="$DB/results/G1-edge"; mkdir -p "$OUT"; : > "$OUT/equivalence.txt"
source "$D/states.sh"
for ties in 0 1; do
  C=outbox-db-H02
  docker rm -f -v $C >/dev/null 2>&1
  if [[ $ties == 0 ]]; then "$HS/start-postgres.sh" H02 edge >/dev/null
  else "$HS/start-postgres.sh" H02 none >/dev/null
       docker exec -i $C psql -U postgres -X -q -v key_ties=1 < "$HS/seed-edge.sql"; docker exec $C psql -U postgres -qc "VACUUM ANALYZE"; fi
  docker exec $C psql -U postgres -XtAc "select 'edge key_ties=$ties rows=' || count(*) || ' new=' || count(*) filter (where status='NEW') || ' parts_used=' || count(distinct partition_no) from outbox_record" >> "$OUT/equivalence.txt"
  for H in H01 H02 H03 H04; do
    case $H in H02) state_H02;; H03) state_H03;; H04) state_H04;; esac
    for g in 0 1; do echo "## $H key_ties=$ties generic=$g" >> "$OUT/equivalence.txt"; "$D/eq-suite.sh" $C $H generic=$g >> "$OUT/equivalence.txt" 2>&1; done
  done
  docker rm -f -v $C >/dev/null
done
