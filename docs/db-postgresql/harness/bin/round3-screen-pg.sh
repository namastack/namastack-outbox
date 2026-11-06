#!/usr/bin/env bash
# Driver: phase 5 round 3, PostgreSQL, stage 0 (pages form in the S10 loop) + stage 1 (screen). Spec: experiments/round3-spec.md. Run from anywhere:
#   nohup docs/db-postgresql/harness/bin/round3-screen-pg.sh > /dev/null 2>&1 &     (env: STAGES="0 1" default, SCENARIOS="S2 S4 S7" default (stage 1), REDO=1)
# Logs: results/logs/round3-screen-pg.log (steps, pass order, variant order per label), round3-screen-pg.out (tool output), failures: results/logs/failures-round3.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, concurrency-p4-r0.txt) unless REDO=1. Results: results/<variant>/postgres/<label>-R3/.
# Override inputs: experiments/R3-<variant>/{ddl.sql,queries/}; v1b and E04 reuse experiments/R2-v1b, R2-E04.
#
# STAGE 0 (scenario S2 data, DDL v1b): forms PF-UN PF-IN PF-NS PF-ANY x modes {def, np (= --pg-no-parallel)} x 4 passes of S10 (pollers 4, rate 0, 60 s,
#   fresh `prepare` before each, --capture-plans: per-poller pages plan-cache counts, auto_explain >= 5 ms, final plan capture). 8 combinations per pass, order rotated by 2 per pass (logged).
#   Labels S10-<form>-<mode>-p<pass>-R3 (variant dir = form). Plus isolated bench on S8-S2 (--groups HOT, 2 passes, labels S8-S2-R3 / S8-S2-R3b, rotated) for the 4 forms;
#   --pages-client-sort is passed for all forms and acts only on forms without ORDER BY (PF-NS, PF-ANY).
# STAGE 1 (screen, --groups HOT,MON, warmup 5, reps 20, --prepare, rotated variant order per label, logged):
#   19 variants: v1b E04 U0 UAB1 UAB2 UABC UAB2C UABC2 UABC2D2 UABD1 UABD2 UABCD1 UABCD2 UABC-L UABCD2-NS PA1 PA2 PC1 PC2 on S8-S2 (S2 data), S8-S4 (S4), S8-S7 (S7), all --owned mod4;
#   S16-S2, S16-S4 (--owned = explicit list of 16 partitions 0,16,...,240) for v1b UABCD2 PA1 PC1;
#   write cost on S2 (label S2-wc-R3) for all DDL variants except U0 (= v1b DDL) and UABC-L (= UABC DDL); repeat (S2-wc2-R3) for v1b UAB1 UABCD2.
#   --pages-client-sort for every variant whose pages SQL has no ORDER BY (everything except v1b, E04, PF-UN, PF-IN).
# RUNTIME ESTIMATE (step times from results/logs/round2-pg.log: v1-class variant bench incl. prepare S2 1.4 min, S4 2.9, S7 3.4; with --groups HOT,MON assumed
#   S8-S2 1.3, S8-S4 2.3, S8-S7 3.0; S10 run incl. prepare ~2.4 min; writecost incl. prepare ~1.7 min):
#   stage 0: 32 S10 x 2.4 = 1.3 h + 8 isolated x 1.3 = 0.2 h; stage 1: 19 x (1.3 + 2.3 + 3.0) = 2.1 h, S16 4 x (1.3 + 2.3) = 0.25 h, writecost 22 x 1.7 = 0.6 h, gens ~0.1 h
#   => about 4.5 hours in total (stage 0 ~1.5 h, stage 1 ~3 h). Free disk at preparation time: ~21 GB (round 2 ran on the same volume); S4/S7 gens are the largest, keep >= 12 GB free.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LOG="$L/round3-screen-pg.log"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
SFX=${R3_SUFFIX:--R3}   # R3_SUFFIX/VARIANTS/BENCHARGS/S10SECONDS/S0SC (stage 0 scenario)/SCENARIOS=S1: smoke tests only
S0=${S0SC:-S2}
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
run(){ log "RUN: $*"; "$@" >>"$L/round3-screen-pg.out" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$L/failures-round3.txt"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>

# name -> "catalog-variant|worktree|ddl dir (under experiments)|queries dir"
declare -A DEF=(
 [v1b]="v1|v1|R2-v1b|R2-v1b" [E04]="v1|v1|R2-E04|R2-E04" )
S1V="U0 UAB1 UAB2 UABC UAB2C UABC2 UABC2D2 UABD1 UABD2 UABCD1 UABCD2 UABC-L UABCD2-NS PA1 PA2 PC1 PC2"
for v in PF-UN PF-IN PF-NS PF-ANY $S1V; do DEF[$v]="v1|v1|R3-$v|R3-$v"; done
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
# client sort: every variant whose pages SQL has no ORDER BY
cs(){ case "$1" in v1b|E04|PF-UN|PF-IN) ;; *) echo "--pages-client-sort";; esac; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }

ALL=(v1b E04 $S1V); [ -n "${VARIANTS:-}" ] && ALL=($VARIANTS)
LABELS=(S8-S2 S8-S4 S8-S7 S16-S2 S16-S4)
labidx(){ local i; for i in "${!LABELS[@]}"; do [ "${LABELS[$i]}" = "$1" ] && { echo $i; return; }; done; echo 0; }
OWN16=$(seq -s, 0 16 240)

# B <variant> <label> <scenario> [extra args]: fresh prepare + screen bench
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) $(cs $v) --scenario $sc --label "$lab$SFX" --prepare --groups HOT,MON --warmup 5 --reps 20 ${BENCHARGS:-} "$@"; }
matrix(){ local lab=$1 sc=$2; shift 2; local list=("${ALL[@]}")
  if [[ $lab == S16-* ]]; then list=(); for v in v1b UABCD2 PA1 PC1; do [[ " ${ALL[*]} " == *" $v "* ]] && list+=($v); done; fi
  local off=$(( 3 * $(labidx $lab) % ${#list[@]} )); local ord; ord=$(rot $off "${list[@]}" | tr '\n' ' ')
  log "--- label $lab (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
WC(){ local v=$1 lab=$2 sc=${3:-S2} n=${4:-}   # writecost on fresh prepare
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX $n"; }

# S10 <form> <mode def|np> <pass>
S10(){ local f=$1 m=$2 p=$3 np="" lab="S10-$1-$2-p$3$SFX"; [ "$m" = np ] && np="--pg-no-parallel"
  step "$(res $f $lab)/concurrency-p4-r0.txt" bash -c "bin/bench prepare $D $(va $f) --scenario $S0 --label $lab && bin/bench concurrency $D $(va $f) $(cs $f) $np --capture-plans --scenario $S0 --label $lab --pollers 4 --rate 0 --seconds ${S10SECONDS:-60}"; }

stage0(){
  log "=== stage 0: pages form in the S10 loop"; run bin/bench gen $D --scenario $S0
  local combos=() f m; for f in PF-UN PF-IN PF-NS PF-ANY; do for m in def np; do combos+=("$f:$m"); done; done
  local p; for p in 1 2 3 4; do
    local ord; ord=$(rot $(( 2 * (p - 1) )) "${combos[@]}" | tr '\n' ' ')
    log "--- stage 0 pass $p, order: $ord"
    for c in $ord; do S10 ${c%%:*} ${c##*:} $p; done
  done
  # isolated bench, S8-S2, HOT group, 2 passes in rotated order (client sort acts on PF-NS, PF-ANY only)
  local forms=(PF-UN PF-IN PF-NS PF-ANY) pass lab
  for pass in 1 2; do
    lab="S8-S2-R3"; [ $pass = 2 ] && lab="S8-S2-R3b"
    local ord2; ord2=$(rot $(( 2 * (pass - 1) )) "${forms[@]}" | tr '\n' ' ')
    log "--- stage 0 isolated $lab order: $ord2"
    for f in $ord2; do step "$(res $f $lab$SFX)/timings.csv" bin/bench bench $D $(va $f) $(cs $f) --scenario $S0 --label "$lab$SFX" --prepare --owned mod4 --groups HOT --warmup 5 --reps 20 ${BENCHARGS:-}; done
  done
}

stage1(){
  local sc
  for sc in ${SCENARIOS:-S2 S4 S7}; do
    log "=== stage 1 scenario $sc"; run bin/bench gen $D --scenario $sc
    case $sc in
    S2|S1) matrix S8-S2 $sc --owned mod4; matrix S16-S2 $sc --owned $OWN16   # S1: smoke only
      local v; for v in "${ALL[@]}"; do case $v in U0|UABC-L) ;; *) WC $v S2-wc $sc;; esac; done
      for v in v1b UAB1 UABCD2; do [[ " ${ALL[*]} " == *" $v "* ]] && WC $v S2-wc2 $sc; done ;;
    S4) matrix S8-S4 S4 --owned mod4; matrix S16-S4 S4 --owned $OWN16 ;;
    S7) matrix S8-S7 S7 --owned mod4 ;;
    esac
  done
}

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== round 3 start, stages: ${STAGES:-0 1}, free disk: $(df -h --output=avail "$R" | tail -1)"
for st in ${STAGES:-0 1}; do case $st in 0) stage0;; 1) stage1;; esac; done
log "DONE round 3 screen"
