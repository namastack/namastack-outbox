#!/usr/bin/env bash
# Driver: SQL-level matrix for one dialect (non-PostgreSQL). usage: DIALECT=mysql [SCENARIOS="S1 S2 S4 S5 S7"] bin/matrix-sql.sh
# Per snapshot: gen, then main and v1 bench (--prepare each), equivalence, writecost on S2.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
DL=${DIALECT:?}; R="$H/../results"; L="$R/logs"; mkdir -p "$L"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect $DL"
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$L/matrix-$DL.log"; }
run(){ log "RUN: $*"; "$@" >>"$L/matrix-$DL.out" 2>&1; rc=$?; [ $rc -ne 0 ] && { log "FAILED rc=$rc: $*"; echo "FAILED($DL) rc=$rc: $*" >> "$L/failures.txt"; }; return $rc; }
va(){ local w=WT_$1; echo "--variant $1 --worktree ${!w}"; }
specs_S1=("S1|S1|"); specs_S2=("S2|S2|" "S8-S2|S2|--owned mod4"); specs_S4=("S4|S4|" "S8-S4|S4|--owned mod4")
specs_S5=("S5|S5|"); specs_S7=("S7|S7|")
doscen(){ local sc=$1; local -n specs=specs_$sc
  log "=== $sc gen (free: $(df -h / | tail -1 | awk '{print $4}'))"
  [ "${SKIPGEN:-0}" = 1 ] || { run bin/bench gen $D --scenario $sc || return; }
  for s in "${specs[@]}"; do IFS='|' read lab scn ex <<<"$s"
    [[ $lab == S8-* ]] && ex="$ex --groups HOT,WRITE,MON"
    for v in main v1; do [ -f "$R/$v/$DL/$lab/timings.csv" ] && [ "${REDO:-0}" != 1 ] && continue; run bin/bench bench $D $(va $v) --scenario $scn --label $lab --prepare $ex; done; done
  if [ $sc = S2 ] && [ $DL != h2 ]; then
    for v in main v1; do run bin/bench prepare $D $(va $v) --scenario S2 --label S2; run bin/bench writecost $D $(va $v) --scenario S2 --label S2; done; fi
  if [[ " S1 ${EQUIV_BIG:+S2 S4 S5} " == *" $sc "* ]]; then
    HARNESS_SAMPLE_ORDERED=1 run bin/bench equiv $D --a main --b v1 --worktree-a $WT_main --worktree-b $WT_v1 --scenario $sc; fi
}
for sc in ${SCENARIOS:-S1 S2 S4 S5 S7}; do doscen $sc; done
log "DONE $DL"
