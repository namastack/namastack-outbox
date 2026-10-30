#!/usr/bin/env bash
# G1 iteration 2, one scenario on one container, states applied in place (post-seed):
#   H04 indexes -> H05 (S/F queries, H05-X stats) -> H06 -> H08a (+ full-suite regression) -> H07a -> H07b -> H09a -> H09b
# Usage: run-scenario.sh <S> <container-suffix>   (results/<H>-<S>/, reference numbers: results/G1ctl-<S>, results/H04-<S>)
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; N="$2"; C=outbox-db-$N; RE="$HS/run-explain.sh"
source "$D/states.sh"
docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" "$N" "$S" || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
KEYQ="findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter"
sizes() { docker exec $C psql -U postgres -XtA -F' ' -c "SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)), pg_relation_size(indexrelid) FROM pg_stat_user_indexes WHERE relname='outbox_record' ORDER BY 1" > "$1"; }
qlist() { # qdir -> existing target query names
  local qd=$1 out=""
  for q in findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findRecordKeysInPartitions_nofilter_fence \
           findRecordKeysInPartitions_nofilter_scalar findRecordKeysInPartitions_nofilter_implied findRecordKeysInPartitions_filter_orig findOldestDueNextRetryAtByPartitions_filter; do
    [[ -f "$qd/$q.sql" ]] && out="$out $q"
  done; echo $out
}
measure() { # label qdir [extra-batch-runs]
  local L=$1 QD=$2 EX=${3:-0} T; T=$(qlist "$QD"); rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"; sizes "$DB/results/$L/sizes.txt"
  "$RE" -c $C -s "$L" -q "$QD" --set max_parallel_workers=0 --tag serial $T
  "$RE" -c $C -s "$L" -q "$QD" --generic --set max_parallel_workers=0 --tag serial $T
  "$RE" -c $C -s "$L" -q "$QD" --costs -r 1 --set max_parallel_workers=0 --tag serial $(qlist "$QD" | tr ' ' '\n' | grep RecordKeys)
  local W=""; for t in $T; do W="$W $t@parts=64,partlist_override=999"; done
  "$RE" -c $C -s "$L" -q "$QD" --set max_parallel_workers=0 --tag serial-worst $W
  if [[ $EX == 1 ]]; then for b in 100 1000; do
    "$RE" -c $C -s "$L" -q "$QD" --set max_parallel_workers=0 --tag serial $(for t in $T; do [[ $t == findRecordKeys* ]] && echo "$t@parts=64,batch=$b $t@parts=256,batch=$b"; done)
  done; fi
}
eqs() { mkdir -p "$DB/results/$1-$S"; "$D/eq-suite.sh" $C $2 > "$DB/results/$1-$S/equivalence.txt" 2>&1; echo "eq $1: $(grep -c "^PASS" "$DB/results/$1-$S/equivalence.txt") pass / $(grep -c "^FAIL" "$DB/results/$1-$S/equivalence.txt") fail"; }
fullsuite() { # label qdir
  local L=$1 QD=$2; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"; sizes "$DB/results/$L/sizes.txt"
  "$RE" -c $C -s "$L" -q "$QD"
  "$RE" -c $C -s "$L" -q "$QD" --generic
  "$RE" -c $C -s "$L" -q "$QD" --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findCompletedRecordsBefore findFailedRecordsBefore deleteCompletedRecords_selectIds deleteFailedRecords_selectIds
  "$RE" -c $C -s "$L" -q "$QD" --generic --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter
}
cleanup_sel() { # label  (G5 selects on the current state, baseline SQL)
  "$RE" -c $C -s "$1" -q "$Q/base" --set max_parallel_workers=0 --tag serial findCompletedRecordsBefore deleteCompletedRecords_selectIds findFailedRecordsBefore
}
EXP=0; [[ $S == B || $S == D ]] && EXP=1
for v in a b; do
  state_H09n_outer; measure H09n$v-outer-$S "$Q/H09n$v" $EXP; eqs H09n$v-outer H09n$v
done
echo "scenario $S done"
