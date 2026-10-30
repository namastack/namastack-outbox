#!/usr/bin/env bash
# Equivalence on the edge seed (key_ties 0 and 1) for every variant/state; container outbox-db-H16.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
C=outbox-db-H16; EQ="$DB/results/G2i2-eq-edge.txt"; : > "$EQ"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
PSQLC() { docker exec -i "$C" psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
va() { PSQLC -c "VACUUM ANALYZE outbox_record" -c CHECKPOINT; }
for ties in 0 1; do
  REF="$D/ref/edge$ties"; rm -rf "$REF"; docker rm -f -v $C >/dev/null 2>&1
  "$HS/start-postgres.sh" H16 none >/dev/null; docker exec -i $C psql -U postgres -X -q -v key_ties=$ties < "$HS/seed-edge.sql"; va
  docker exec $C psql -U postgres -XtAc "select '## edge key_ties=$ties rows=' || count(*) || ' new=' || count(*) filter (where status='NEW') || ' keys=' || count(distinct record_key) || ' invariant_violations=' || count(*) filter (where status='NEW' and completed_at is not null) from outbox_record" >> "$EQ"
  eqs() { for k in 10 100 1000; do for g in 0 1; do "$D/eq2.sh" $C "$REF" "$1" "$Q/$2/findIncompleteRecordPages.sql" keys=$k generic=$g >> "$EQ"; done; done
          for g in 0 1; do "$D/eq2.sh" $C "$REF" "$1" "$Q/ctl/findIncompleteRecordsByRecordKey.sql" generic=$g >> "$EQ"; done; }
  eqs ref ctl
  PSQLC < "$DB/experiments/H08/indexes.sql"; va; for v in ctl L Ln Lc Lcn Lo Lon P1 P2 LP; do eqs h08a-$v $v; done
  PSQLC -c "CREATE INDEX idx_outbox_record_record_key_completed_created_id ON outbox_record (record_key, completed_at, created_at, id)" -c "DROP INDEX idx_outbox_record_record_key_completed_created"; va
  for v in ctl Lc Lcn Lo Lon; do eqs u2b-$v $v; done
  PSQLC -c "CREATE INDEX idx_outbox_record_record_key_completed_created ON outbox_record (record_key, completed_at, created_at)" -c "DROP INDEX idx_outbox_record_record_key_completed_created_id" -c "CREATE INDEX idx_outbox_record_record_key_status_created ON outbox_record (record_key, status, created_at, id)"; va
  for v in ctl L Ln Lc Lon; do eqs h10-$v $v; done
  if [[ $ties == 0 ]]; then
    PSQLC -c "UPDATE outbox_record SET completed_at = created_at WHERE id = (SELECT id FROM outbox_record WHERE status='NEW' AND record_key='edge-101' ORDER BY created_at LIMIT 1)"
    echo "## negative control (NEW row with completed_at set); FAIL expected for Lc/Lo" >> "$EQ"; eqs h10-negctl Lo; eqs h10-negctl ctl
  fi
done
echo "PASS: $(grep -c '^PASS' "$EQ")  FAIL: $(grep -c '^FAIL' "$EQ")"
