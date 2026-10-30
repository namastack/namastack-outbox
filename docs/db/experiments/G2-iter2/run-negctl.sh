#!/usr/bin/env bash
# Negative control on the edge seed: one NEW row of key edge-101 gets completed_at set; completed_at IS NULL variants must FAIL.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"; C=outbox-db-H16; REF="$D/ref/negctl"; rm -rf "$REF"
OUT="$DB/results/G2i2-eq-negctl.txt"; : > "$OUT"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
"$HS/start-postgres.sh" H16 none >/dev/null; docker exec -i $C psql -U postgres -X -q -v key_ties=0 < "$HS/seed-edge.sql"
"$D/eq2.sh" $C "$REF" ref "$Q/ctl/findIncompleteRecordPages.sql" keys=1000 generic=0 >> "$OUT"
docker exec $C psql -U postgres -Xqc "UPDATE outbox_record SET completed_at = created_at WHERE id = (SELECT id FROM outbox_record WHERE status='NEW' AND record_key='edge-101' ORDER BY created_at LIMIT 1)" -c "VACUUM ANALYZE outbox_record"
for v in ctl L Lc Lo Lon; do "$D/eq2.sh" $C "$REF" negctl-$v "$Q/$v/findIncompleteRecordPages.sql" keys=1000 generic=0 >> "$OUT"; done
