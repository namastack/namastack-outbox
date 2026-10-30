#!/usr/bin/env bash
# G2 iteration 1, one scenario on one container (outbox-db-H11), index states applied in place (post-seed):
#   control -> H11 (SQL) -> H13 (SQL) -> +H02 idx: H12, H13+H11 -> +H04 idx: H12 -> H12-alt (B, D)
#   -> H10 add-only -> H10 replace (drop record_key_created) -> H10 noid (B, D).
# Per state: target queries custom / generic / costs (D: + rpc11, nobitmap), equivalence vs control output.
# Usage: run-scenario.sh <S> [--reg]   (--reg: full read suite on control, H10 add-only and H10 replace)
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; shift; REG=0; [[ "${1:-}" == --reg ]] && REG=1
C=outbox-db-H11; RE="$HS/run-explain.sh"; REF="$D/ref/$S"; rm -rf "$REF"
source "$D/states.sh"
T="findIncompleteRecordPages findIncompleteRecordsByRecordKey"
docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" H11 "$S" || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
EQ="$DB/results/G2-eq-$S.txt"; : > "$EQ"
docker exec $C psql -U postgres -XtAc "select 'invariant NEW with completed_at: ' || count(*) from outbox_record where status='NEW' and completed_at is not null" | tee -a "$EQ"

sizes() { docker exec $C psql -U postgres -XtA -F' ' -c "SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)), pg_relation_size(indexrelid) FROM pg_stat_user_indexes WHERE relname='outbox_record' ORDER BY 1" > "$1"; }
measure() { # label qdir [tag]
  local L=$1 QD=$2 t=${3:-} tg=() ; [[ -n $t ]] && tg=(--tag $t)
  mkdir -p "$DB/results/$L"; [[ -n $t ]] || { rm -f "$DB/results/$L"/*; sizes "$DB/results/$L/sizes.txt"; }
  local qs=$T; [[ -f "$QD/.pages-only" ]] && qs=findIncompleteRecordPages
  "$RE" -c $C -s "$L" -q "$QD" "${tg[@]}" $qs
  "$RE" -c $C -s "$L" -q "$QD" --generic "${tg[@]}" $qs
  "$RE" -c $C -s "$L" -q "$QD" --costs -r 1 ${t:+--tag $t} findIncompleteRecordPages
  if [[ $S == D ]]; then
    "$RE" -c $C -s "$L" -q "$QD" --set random_page_cost=1.1 --tag rpc11${t:+-$t} $qs
    "$RE" -c $C -s "$L" -q "$QD" --generic --set random_page_cost=1.1 --tag rpc11${t:+-$t} $qs
    "$RE" -c $C -s "$L" -q "$QD" --set enable_bitmapscan=off --tag nobitmap${t:+-$t} $qs
    "$RE" -c $C -s "$L" -q "$QD" --generic --set enable_bitmapscan=off --tag nobitmap${t:+-$t} $qs
  fi
}
eqs() { # tag qdir
  for k in 10 100 1000; do for g in 0 1; do
    "$D/eq.sh" $C "$REF" "$1" "$2/findIncompleteRecordPages.sql" keys=$k generic=$g >> "$EQ"; done; done
  for g in 0 1; do "$D/eq.sh" $C "$REF" "$1" "$2/findIncompleteRecordsByRecordKey.sql" generic=$g >> "$EQ"; done
}
fullsuite() { # label
  local L=$1; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"; sizes "$DB/results/$L/sizes.txt"
  "$RE" -c $C -s "$L" -q "$Q/base"
  "$RE" -c $C -s "$L" -q "$Q/base" --generic
  "$RE" -c $C -s "$L" -q "$Q/base" --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findCompletedRecordsBefore findFailedRecordsBefore deleteCompletedRecords_selectIds deleteFailedRecords_selectIds
  "$RE" -c $C -s "$L" -q "$Q/base" --generic --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter
}

eqs ref "$Q/base"
measure G2ctl-$S "$Q/base"; [[ $REG == 1 ]] && fullsuite G2ctl-reg-$S
measure H11-$S "$Q/H11"; eqs H11 "$Q/H11"
measure H13-$S "$Q/H13"; eqs H13 "$Q/H13"
state_H02; measure H12-$S "$Q/H12"; eqs H12-h02 "$Q/H12"
measure H12-$S "$Q/base" origq; eqs H02-origq "$Q/base"
measure H13-$S "$Q/H13c" h11-h02; eqs H13c-h02 "$Q/H13c"
state_H04; measure H12h04-$S "$Q/H12"; eqs H12-h04 "$Q/H12"
measure H13-$S "$Q/H13c" h11-h04
if [[ $S == B || $S == D ]]; then state_H12alt; measure H12alt-$S "$Q/base"; eqs H12alt "$Q/base"; fi
state_H10add; measure H10add-$S "$Q/base"; eqs H10add "$Q/base"; [[ $REG == 1 ]] && fullsuite H10add-reg-$S
measure H13-$S "$Q/H13" h10add
state_H10; measure H10-$S "$Q/base"; eqs H10 "$Q/base"; [[ $REG == 1 ]] && fullsuite H10-reg-$S
measure H11-$S "$Q/H11" h10
if [[ $S == B || $S == D ]]; then state_H10noid; measure H10noid-$S "$Q/base"; eqs H10noid "$Q/base"; fi
grep -c PASS "$EQ"; grep -E '^FAIL' "$EQ"
echo "scenario $S done"
