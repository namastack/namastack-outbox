#!/usr/bin/env bash
# G1 iteration 1, one scenario on one container (outbox-db-H01), states applied in place (post-seed DDL):
#   base (control) -> H01 (SQL only) -> +H02 index -> +H03 index -> H04 (H03 partition index dropped, H04 created index)
#   [-> H03b on B/D]. Per state: target queries serial / custom / generic / generic-serial, equivalence,
#   and (with --reg) the full baseline query suite for regression.
# Usage: run-scenario.sh <S> [--reg] [--h03b]
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
S="$1"; shift; REG=0; H3B=0
for a in "$@"; do case $a in --reg) REG=1;; --h03b) H3B=1;; esac; done
C=outbox-db-H01; RE="$HS/run-explain.sh"
source "$D/states.sh"
T="findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter findOldestDueNextRetryAtByPartitions_nofilter"
docker rm -f -v $C >/dev/null 2>&1
"$HS/start-postgres.sh" H01 "$S" || exit 1
trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT

sizes() { docker exec $C psql -U postgres -XtA -F' ' -c "SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)), pg_relation_size(indexrelid) FROM pg_stat_user_indexes WHERE relname='outbox_record' ORDER BY 1" > "$1"; }
measure() { # label qdir
  local L=$1 QD=$2; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"; sizes "$DB/results/$L/sizes.txt"
  "$RE" -c $C -s "$L" -q "$QD" --set max_parallel_workers=0 --tag serial $T
  "$RE" -c $C -s "$L" -q "$QD" $T
  "$RE" -c $C -s "$L" -q "$QD" --generic $T
  "$RE" -c $C -s "$L" -q "$QD" --generic --set max_parallel_workers=0 --tag serial $T
  "$RE" -c $C -s "$L" -q "$QD" --costs -r 1 --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter
  # worst case: owned partitions without records (custom serial)
  "$RE" -c $C -s "$L" -q "$QD" --set max_parallel_workers=0 --tag serial-worst \
     findRecordKeysInPartitions_filter@parts=64,partlist_override=999 findRecordKeysInPartitions_nofilter@parts=64,partlist_override=999 findOldestDueNextRetryAtByPartitions_filter@parts=64,partlist_override=999
}
fullsuite() { # label  (baseline SQL, mirrors run-suite.sh minus writes)
  local L=$1; rm -rf "$DB/results/$L"; mkdir -p "$DB/results/$L"; sizes "$DB/results/$L/sizes.txt"
  "$RE" -c $C -s "$L" -q "$Q/base"
  "$RE" -c $C -s "$L" -q "$Q/base" --generic
  "$RE" -c $C -s "$L" -q "$Q/base" --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findCompletedRecordsBefore findFailedRecordsBefore deleteCompletedRecords_selectIds deleteFailedRecords_selectIds
  "$RE" -c $C -s "$L" -q "$Q/base" --generic --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter
}
eqs() { mkdir -p "$DB/results/$1-$S"; "$D/eq-suite.sh" $C $1 > "$DB/results/$1-$S/equivalence.txt" 2>&1; cat "$DB/results/$1-$S/equivalence.txt"; }

measure G1ctl-$S "$Q/base"
measure H01-$S "$Q/H01"; eqs H01
state_H02; measure H02-$S "$Q/H02"; eqs H02; [[ $REG == 1 ]] && fullsuite H02-reg-$S
state_H03; measure H03-$S "$Q/H03"; eqs H03; [[ $REG == 1 ]] && fullsuite H03-reg-$S
state_H04; measure H04-$S "$Q/H04"; eqs H04; [[ $REG == 1 ]] && fullsuite H04-reg-$S
for b in 100 1000; do
  "$RE" -c $C -s H04-$S -q "$Q/H04" --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter@parts=64,batch=$b findRecordKeysInPartitions_filter@parts=256,batch=$b findRecordKeysInPartitions_nofilter@parts=64,batch=$b
done
if [[ $H3B == 1 ]]; then state_H03b; measure H03b-$S "$Q/base"; fi
echo "scenario $S done"
