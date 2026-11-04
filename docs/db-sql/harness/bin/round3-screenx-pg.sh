#!/usr/bin/env bash
# Driver: phase 5 round 3, PostgreSQL, screen extension (spec: experiments/round3-spec.md "Screen extension"). Run from anywhere:
#   nohup docs/db-sql/harness/bin/round3-screenx-pg.sh > /dev/null 2>&1 &     (env: STAGES="X0 X1" default (X0 then X1), SCENARIOS="S2 S4 S7" (X1), REDO=1)
# Logs: results/logs/round3-screenx-pg.log (steps, pass order, variant order per label), round3-screenx-pg.out (tool output), failures: results/logs/failures-round3x.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, concurrency-p4-r0.txt) unless REDO=1. Results: results/<variant>/postgres/<label>-R3X/.
# Override inputs: experiments/R3X-<variant>/{ddl.sql,queries/}; references reuse experiments/R2-v1b, R3-UABCD2, R3-UABC-L. Aborts when free disk < 12 GB.
#
# X0 (scenario S2 data, DDL sets UABCD2 = A,B1,C,D2 and UABC = A,B1,C): forms PF-UN PF-IN PF-NS PF-ANY PF-UNNS0 -> 10 combinations (variant "<form>-<set>"),
#   4 passes of S10 (pollers 4, rate 0, 60 s, default plan mode, fresh `prepare` before each, --capture-plans), order rotated by 3 per pass (logged).
#   Labels S10-def-p<pass>-R3X (variant dir = <form>-<set>).
# X1 (screen, --groups HOT,MON, warmup 5, reps 20, --prepare, rotated variant order per label, logged):
#   15 variants: v1b UABCD2 UABC-L (references) UABC3 UABC3D2 UABC3-L PL-A PL-C PL-AC PAC PD UA2BCD2 UA3BCD2 UA2BC-L UA3BC-L on S8-S2 (S2), S8-S4 (S4), S8-S7 (S7), --owned mod4;
#   S16-S2, S16-S4 (16 partitions 0,16,...,240) for v1b UABCD2 PL-A PL-C PL-AC; write cost on S2 (label S2-wc-R3X) for every distinct DDL
#   (v1b UABCD2 UABC UABC3 UABC3D2 PL-A PL-C PL-AC PAC PD UA2BCD2 UA3BCD2 UA2BC-L UA3BC-L; UABC-L runs the UABC DDL, UABC3-L the UABC3 DDL).
#   --pages-client-sort for every variant whose pages SQL has no ORDER BY after LATERAL / at all (all except v1b, PF-UN-*, PF-IN-*).
# RUNTIME ESTIMATE (step times of results/logs/round3-screen-pg.log, which took 3.3 h in total: S10 run incl. prepare ~2.0 min (32 runs + 8 isolated in 74 min),
#   S8-S2 bench ~1.1 min, S8-S4 ~2.3, S8-S7 ~1.2, S16 ~1.1 / 2.4, writecost ~1.5):
#   X0: 40 x 2.0 = 1.35 h; X1: 15 x (1.1 + 2.3 + 1.2) = 1.15 h, S16 5 x (1.1 + 2.4) = 0.3 h, writecost 14 x 1.5 = 0.35 h, gens ~0.15 h => X1 ~2.0 h
#   => about 3.4 hours in total (X0 ~1.4 h, X1 ~2.0 h). Keep >= 12 GB free (the S4/S7 gens are the largest).
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LOG="$L/round3-screenx-pg.log"; FAIL="$L/failures-round3x.txt"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
SFX=${R3_SUFFIX:--R3X}   # R3_SUFFIX/VARIANTS/BENCHARGS/S10SECONDS/S0SC (X0 scenario)/SCENARIOS=S1/X0FORMS/X0SETS/PASSES: smoke tests only
S0=${S0SC:-S2}
MINGB=${MINGB:-12}
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
diskcheck(){ local g; g=$(df -BG --output=avail "$R" | tail -1 | tr -dc 0-9); if [ "$g" -lt "$MINGB" ]; then log "ABORT: free disk ${g} GB < ${MINGB} GB"; echo "ABORT: free disk ${g} GB" >> "$FAIL"; exit 3; fi; }
run(){ diskcheck; log "RUN: $*"; "$@" >>"$L/round3-screenx-pg.out" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$FAIL"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>

# name -> "catalog-variant|worktree|ddl dir (under experiments)|queries dir"
declare -A DEF=(
 [v1b]="v1|v1|R2-v1b|R2-v1b" [UABCD2]="v1|v1|R3-UABCD2|R3-UABCD2" [UABC-L]="v1|v1|R3-UABC-L|R3-UABC-L" )
X1NEW="UABC3 UABC3D2 UABC3-L PL-A PL-C PL-AC PAC PD UA2BCD2 UA3BCD2 UA2BC-L UA3BC-L"
X0FORMS=${X0FORMS:-"PF-UN PF-IN PF-NS PF-ANY PF-UNNS0"}; X0SETS=${X0SETS:-"UABCD2 UABC"}
for v in $X1NEW; do DEF[$v]="v1|v1|R3X-$v|R3X-$v"; done
for s in $X0SETS; do for f in $X0FORMS; do DEF[$f-$s]="v1|v1|R3X-$f-$s|R3X-$f-$s"; done; done
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
# client sort: every variant whose pages SQL has no ORDER BY (the flag has no effect on a pages SQL with ORDER BY, but is kept off for the ordered ones)
cs(){ case "$1" in v1b|PF-UN-*|PF-IN-*) ;; *) echo "--pages-client-sort";; esac; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }

ALL=(v1b UABCD2 UABC-L $X1NEW); [ -n "${VARIANTS:-}" ] && ALL=($VARIANTS)
LABELS=(S8-S2 S8-S4 S8-S7 S16-S2 S16-S4)
labidx(){ local i; for i in "${!LABELS[@]}"; do [ "${LABELS[$i]}" = "$1" ] && { echo $i; return; }; done; echo 0; }
OWN16=$(seq -s, 0 16 240)

B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) $(cs $v) --scenario $sc --label "$lab$SFX" --prepare --groups HOT,MON --warmup 5 --reps 20 ${BENCHARGS:-} "$@"; }
matrix(){ local lab=$1 sc=$2; shift 2; local list=("${ALL[@]}")
  if [[ $lab == S16-* ]]; then list=(); for v in v1b UABCD2 PL-A PL-C PL-AC; do [[ " ${ALL[*]} " == *" $v "* ]] && list+=($v); done; fi
  [ ${#list[@]} -eq 0 ] && return 0
  local off=$(( 3 * $(labidx $lab) % ${#list[@]} )); local ord; ord=$(rot $off "${list[@]}" | tr '\n' ' ')
  log "--- label $lab (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
WC(){ local v=$1 lab=$2 sc=${3:-S2}
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX"; }

# S10 <variant> <pass>  (variant = <form>-<set>, label S10-def-p<pass>)
S10(){ local f=$1 p=$2 lab="S10-def-p$2$SFX"
  step "$(res $f $lab)/concurrency-p4-r0.txt" bash -c "bin/bench prepare $D $(va $f) --scenario $S0 --label $lab && bin/bench concurrency $D $(va $f) $(cs $f) --capture-plans --scenario $S0 --label $lab --pollers 4 --rate 0 --seconds ${S10SECONDS:-60}"; }

stageX0(){
  log "=== X0: pages forms in the S10 loop on the proposed indexes"; run bin/bench gen $D --scenario $S0
  local combos=() f s; for s in $X0SETS; do for f in $X0FORMS; do combos+=("$f-$s"); done; done
  local p; for p in ${PASSES:-1 2 3 4}; do
    local ord; ord=$(rot $(( 3 * (p - 1) )) "${combos[@]}" | tr '\n' ' ')
    log "--- X0 pass $p, order: $ord"
    for c in $ord; do S10 $c $p; done
  done
}

stageX1(){
  local sc
  for sc in ${SCENARIOS:-S2 S4 S7}; do
    log "=== X1 scenario $sc"; run bin/bench gen $D --scenario $sc
    case $sc in
    S2|S1) matrix S8-S2 $sc --owned mod4; matrix S16-S2 $sc --owned $OWN16   # S1: smoke only
      local v; for v in "${ALL[@]}"; do case $v in UABC3-L) ;; UABC-L) WC UABC-L S2-wc $sc;; *) WC $v S2-wc $sc;; esac; done
      # note: UABC-L carries the UABC DDL, UABC3-L has the UABC3 DDL (= UABC3, not repeated)
      ;;
    S4) matrix S8-S4 S4 --owned mod4; matrix S16-S4 S4 --owned $OWN16 ;;
    S7) matrix S8-S7 S7 --owned mod4 ;;
    esac
  done
}

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== round 3 screen extension start, stages: ${STAGES:-X0 X1}, free disk: $(df -h --output=avail "$R" | tail -1)"
for st in ${STAGES:-X0 X1}; do case $st in X0) stageX0;; X1) stageX1;; esac; done
log "DONE round 3 screen extension"
