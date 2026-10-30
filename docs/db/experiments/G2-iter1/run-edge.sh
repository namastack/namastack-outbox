#!/usr/bin/env bash
# G2 equivalence on the edge seed (key_ties 0 and 1), every state, custom + generic, plus an H11 negative control.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
C=outbox-db-H12; EQ="$DB/results/G2-eq-edge.txt"; : > "$EQ"
source "$D/states.sh"
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
for ties in 0 1; do
  REF="$D/ref/edge$ties"; rm -rf "$REF"
  docker rm -f -v $C >/dev/null 2>&1
  "$HS/start-postgres.sh" H12 none >/dev/null
  docker exec -i $C psql -U postgres -X -q -v key_ties=$ties < "$HS/seed-edge.sql"; va
  docker exec $C psql -U postgres -XtAc "select '## edge key_ties=$ties rows=' || count(*) || ' new=' || count(*) filter (where status='NEW') || ' keys=' || count(distinct record_key) || ' invariant_violations=' || count(*) filter (where status='NEW' and completed_at is not null) from outbox_record" >> "$EQ"
  eqs() { for k in 10 100 1000; do for g in 0 1; do "$D/eq.sh" $C "$REF" "$1" "$2/findIncompleteRecordPages.sql" keys=$k generic=$g >> "$EQ"; done; done
          for g in 0 1; do "$D/eq.sh" $C "$REF" "$1" "$2/findIncompleteRecordsByRecordKey.sql" generic=$g >> "$EQ"; done; }
  eqs ref "$Q/base"; eqs H11 "$Q/H11"; eqs H13 "$Q/H13"
  state_H02; eqs H12-h02 "$Q/H12"; eqs H13c-h02 "$Q/H13c"
  state_H04; eqs H12-h04 "$Q/H12"
  state_H12alt; eqs H12alt "$Q/base"
  state_H10add; eqs H10add "$Q/base"
  state_H10; eqs H10 "$Q/base"
  state_H10noid; eqs H10noid "$Q/base"
  if [[ $ties == 0 ]]; then
    # negative control: one NEW row of a requested key with completed_at set -> H11/H12 must FAIL
    PSQLC -c "UPDATE outbox_record SET completed_at = created_at WHERE id = (SELECT id FROM outbox_record WHERE status='NEW' AND record_key='edge-10' ORDER BY created_at LIMIT 1)"
    echo "## negative control (NEW row with completed_at set, key edge-10); FAIL expected" >> "$EQ"
    "$D/eq.sh" $C "$REF" H11-negctl "$Q/H11/findIncompleteRecordPages.sql" keys=1000 generic=0 >> "$EQ"
    "$D/eq.sh" $C "$REF" base-negctl "$Q/base/findIncompleteRecordPages.sql" keys=1000 generic=0 >> "$EQ"
  fi
done
echo "PASS: $(grep -c ^PASS "$EQ")  FAIL: $(grep -c ^FAIL "$EQ")"
