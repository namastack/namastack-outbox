#!/usr/bin/env bash
# Driver: PostgreSQL measurement matrix. Per scenario snapshot: run1 (main->v1), repo/writecost/equiv extras,
# then postgres restart (cold) and run2 (labels with -r2, v1->main). Env: SCENARIOS="S1 S2 ..." SKIP_R2_S9S10=1
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; mkdir -p "$L"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$L/matrix-pg.log"; }
run(){ log "RUN: $*"; "$@" >>"$L/matrix-pg.out" 2>&1; rc=$?; [ $rc -ne 0 ] && { log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$L/failures.txt"; }; return $rc; }
va(){ local w=WT_$1; echo "--variant $1 --worktree ${!w}"; }
B(){ local v=$1 lab=$2 sfx=$3 sc=$4; shift 4
  run bin/bench bench $D $(va $v) --scenario $sc --label "$lab$sfx" --prepare "$@"; }
restart(){ log "restart postgres"; bin/db.sh stop postgres >>"$L/matrix-pg.out" 2>&1; bin/db.sh start postgres >>"$L/matrix-pg.out" 2>&1; }
gen(){ run bin/bench gen $D --scenario $1; }
churnrun(){ local v=$1 sfx=$2
  run bin/bench prepare $D $(va $v) --scenario S2 --label S9$sfx
  run bin/bench churn $D $(va $v) --scenario S2 --label S9$sfx --inserts 500000
  run bin/bench bench $D $(va $v) --scenario S2 --label S9$sfx; }
concrun(){ local v=$1 sfx=$2
  for cfg in "1 500" "4 500" "1 0" "4 0"; do set -- $cfg
    run bin/bench prepare $D $(va $v) --scenario S2 --label S10$sfx
    run bin/bench concurrency $D $(va $v) --scenario S2 --label S10$sfx --pollers $1 --rate $2 --seconds 60
  done; }
specs_S1=("S1|S1|"); specs_S2=("S2|S2|" "S8-S2|S2|--owned mod4"); specs_S3=("S3|S3|")
specs_S4=("S4|S4|" "S4-b100|S4|--batch 100" "S8-S4|S4|--owned mod4")
specs_S5=("S5|S5|"); specs_S6=("S6|S6|"); specs_S7=("S7|S7|")
doscen(){ local sc=$1; local -n specs=specs_$sc
  log "=== scenario $sc gen"; bin/db.sh start postgres >>"$L/matrix-pg.out" 2>&1; gen $sc
  log "--- run1"
  for s in "${specs[@]}"; do IFS='|' read lab scn ex <<<"$s"; for v in main v1; do B $v $lab "" $scn $ex; done; done
  if [ $sc = S2 ]; then
    for v in main v1; do churnrun $v ""; done
    for v in main v1; do concrun $v ""; done
    for v in main v1; do run bin/bench prepare $D $(va $v) --scenario S2 --label S2; run bin/bench writecost $D $(va $v) --scenario S2 --label S2; done
  fi
  if [[ " S1 S2 S4 S5 " == *" $sc "* ]]; then
    for e in jdbc jpa; do for v in main v1; do w=WT_$v; run bin/repo-bench $v bench --engine $e $D --worktree ${!w} --scenario $sc; done; done
  fi
  run bin/bench equiv $D --a main --b v1 --worktree-a $WT_main --worktree-b $WT_v1 --scenario $sc
  if [ $sc = S2 ]; then
    E="$R/equiv/postgres/S2"; cp "$E/main-vs-v1.md" "$E/main-vs-v1-all.md" 2>/dev/null
    run bin/bench equiv $D --a main --b v1 --worktree-a $WT_main --worktree-b $WT_v1 --scenario S2 --owned mod4
    cp "$E/main-vs-v1.md" "$E/main-vs-v1-mod4.md" 2>/dev/null; cp "$E/main-vs-v1-all.md" "$E/main-vs-v1.md" 2>/dev/null
  fi
  log "--- run2 (cold)"; restart
  for s in "${specs[@]}"; do IFS='|' read lab scn ex <<<"$s"; for v in v1 main; do B $v $lab -r2 $scn $ex; done; done
  if [ $sc = S2 ] && [ "${SKIP_R2_S9S10:-0}" != 1 ]; then
    for v in v1 main; do churnrun $v -r2; done
    for v in v1 main; do concrun $v -r2; done
  fi
}
for sc in ${SCENARIOS:-S1 S2 S3 S4 S5 S6 S7}; do doscen $sc; done
log "DONE matrix"
