#!/usr/bin/env bash
# G2 iteration 2 query dirs (findIncompleteRecordPages only; ctl also has the per-key query). Run first.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; O="$D/qdirs"; rm -rf "$O"
mk() { mkdir -p "$O/$1"; cp "$DB/harness/queries/_params.sql" "$O/$1/"; }
mk ctl; cp "$DB/harness/queries/findIncompleteRecordPages.sql" "$DB/harness/queries/findIncompleteRecordsByRecordKey.sql" "$O/ctl/"
mk L;   cp "$DB/experiments/H14/queries/findIncompleteRecordPages.sql" "$O/L/"
mk Ln;  cp "$DB/experiments/H14/queries-noorder/findIncompleteRecordPages.sql" "$O/Ln/"
mk Lc;  cp "$DB/experiments/H14/queries-cat/findIncompleteRecordPages.sql" "$O/Lc/"
mk Lcn; sed '/^ORDER BY k.k, o.created_at, o.id;$/{s/.*/;/}' "$O/Lc/findIncompleteRecordPages.sql" > "$O/Lcn/findIncompleteRecordPages.sql"
mk P1;  sed -e "s/AND status = :key_n1/AND status = :key_n1 AND status <> 'COMPLETED'/" -e "s/AND status = 'NEW'$/AND status = 'NEW' AND status <> 'COMPLETED'/" "$O/ctl/findIncompleteRecordPages.sql" > "$O/P1/findIncompleteRecordPages.sql"
mk P2;  sed -e "s/AND status = :key_n1/AND status = :key_n1 AND status IN ('NEW','FAILED')/" -e "s/AND status = 'NEW'$/AND status = 'NEW' AND status IN ('NEW','FAILED')/" "$O/ctl/findIncompleteRecordPages.sql" > "$O/P2/findIncompleteRecordPages.sql"
mk LP;  sed "s/AND r.status = 'NEW'/AND r.status = 'NEW' AND r.status <> 'COMPLETED'/" "$O/L/findIncompleteRecordPages.sql" > "$O/LP/findIncompleteRecordPages.sql"
grep -c "COMPLETED'" "$O"/P1/*.sql "$O"/P2/*.sql "$O"/LP/*.sql; grep -c "^;" "$O/Lcn/findIncompleteRecordPages.sql"
# Lo/Lon: inner ORDER BY includes the (constant NULL) completed_at so the index order (record_key, completed_at, created_at[, id]) matches without a sort
mk Lo;  sed "s/ORDER BY r.created_at, r.id) o/ORDER BY r.completed_at, r.created_at, r.id) o/" "$O/Lc/findIncompleteRecordPages.sql" > "$O/Lo/findIncompleteRecordPages.sql"
mk Lon; sed "s/ORDER BY r.created_at, r.id) o/ORDER BY r.completed_at, r.created_at, r.id) o/" "$O/Lcn/findIncompleteRecordPages.sql" > "$O/Lon/findIncompleteRecordPages.sql"
grep -c "r.completed_at, r.created_at" "$O/Lo/findIncompleteRecordPages.sql" "$O/Lon/findIncompleteRecordPages.sql"
