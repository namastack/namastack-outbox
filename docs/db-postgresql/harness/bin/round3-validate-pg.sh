#!/usr/bin/env bash
# Driver: phase 5 round 3, PostgreSQL, stage 3 (validate). Spec: experiments/round3-spec.md "Stage 3: validate"; stage 2 results: experiments/R3C-confirm.md.
#   nohup docs/db-postgresql/harness/bin/round3-validate-pg.sh > /dev/null 2>&1 &     (env: SCENARIOS="S2 S4 S5 S6 S7" default, REDO=1, VARIANTS="..." optional)
# Logs: results/logs/round3-validate-pg.log (steps, orders, plan checks), round3-validate-pg.out (tool output), failures: results/logs/failures-round3v.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, concurrency-*.txt) unless REDO=1. Restart after an abort: the same command.
# Results: results/<variant>/postgres/<label>-v<pass>-R3V/ (suffix -R3V). Override inputs: experiments/R3C-<variant>/{ddl.sql,queries/} (no new variants);
#   v1c = DDL of R2-v1b + unmodified v1 catalog (no queries dir). Equivalence is done in stage 2 and not repeated.
#
# Variants (4): v1c UABCD2 UABC-L UABC-L-NS. TWO passes of the whole matrix: pass 1 in that order, pass 2 in reversed order (UABC-L-NS UABC-L UABCD2 v1c);
#   postgres is restarted (bin/db.sh stop postgres; start) between the passes. Every label of a pass uses the pass order (no rotation within a label).
# Per pass (gen of each scenario once per pass, cheap):
#   S2: S8-S2 (--owned mod4, --groups HOT,WRITE,MON), S2 (all groups), write cost (S2-wc), S10 on S2 (2 runs per variant, fresh prepare each, order rotated by 2
#       between runs; keys_strict loop, pollers 4, 60 s, --capture-plans, plan check as in confirm: pages plans must use neither
#       idx_outbox_record_status_created_id_incomplete nor idx_outbox_record_status_retry_incomplete; count of slow plans from the `auto_explain:` line is logged),
#       S9 churn.
#   S4: S8-S4, S4, write cost with index sizes (S4-wc), S10 on S4 (same parameters and plan check; the large queue is new in S10).
#   S5: S8-S5, S5.   S6: S8-S6.   S7: S8-S7.
#   Labels: <label>-v<pass>-R3V, warmup 5, reps 20, --prepare.
#   Smoke (SCENARIOS=S1): bench label S1 (BENCHARGS), write cost S1-wc, one S10 run per variant on S1. Env for smoke: R3V_SUFFIX (default -R3V), PASSES (default "1 2"),
#   VARIANTS, BENCHARGS, S10SECONDS (default 60), S10RUNS (default 2).
# RUNTIME ESTIMATE (step times per variant from results/logs/round3-confirm-pg.log, v1-class):
#   S2: S8-S2 1.4 + S2 1.5 + wc 1.3 + S10 2 x 1.9 + S9 6.3 = 14.3 min;  S4: S8-S4 4.6 + S4 8.7 + wc 2.8 + S10 2 x ~1.9 = 19.9 min (S10 on S4 may be longer: larger prepare);
#   S5 0.6 + 0.8; S6 1.3; S7 1.2  => about 38 min per variant per pass; x 4 variants x 2 passes = 5.1 h, plus gens and restart (~0.2 h): about 5.3 hours in total.
#   Disk: free at preparation ~20 GB; the driver aborts below 12 GB. Each step re-prepares one schema (no snapshots are added); stage 2 ran with the same S4/S5/S7 gens
#   from ~24 GB free, so it should be sufficient; the gen of S4 is the largest step.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LOG="$L/round3-validate-pg.log"; OUT="$L/round3-validate-pg.out"; FAIL="$L/failures-round3v.txt"
WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
BASESFX=${R3V_SUFFIX:--R3V}
SFX=$BASESFX
MINGB=12
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
freegb(){ df -BG --output=avail "$R" | tail -1 | tr -dc 0-9; }
run(){ local g; g=$(freegb); if [ "$g" -lt $MINGB ]; then log "ABORT: free disk ${g} GB < ${MINGB} GB"; exit 3; fi
  log "RUN: $*"; "$@" >>"$OUT" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$FAIL"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>

# name -> "catalog-variant|ddl dir (under experiments)|queries dir ('' = none)"
declare -A DEF=( [v1c]="v1|R3C-v1c|" [UABCD2]="v1|R3C-UABCD2|R3C-UABCD2" [UABC-L]="v1|R3C-UABC-L|R3C-UABC-L" [UABC-L-NS]="v1|R3C-UABC-L-NS|R3C-UABC-L-NS" )
va(){ local v=$1; IFS='|' read cv dd qd <<<"${DEF[$v]}"
  local a="--variant $v --worktree $WT_v1"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }

FWD=(v1c UABCD2 UABC-L UABC-L-NS); [ -n "${VARIANTS:-}" ] && FWD=($VARIANTS)
REV=(); for ((i=${#FWD[@]}-1;i>=0;i--)); do REV+=(${FWD[$i]}); done
ALL=("${FWD[@]}")     # pass order, set per pass

# B <variant> <label> <scenario> [extra args]: fresh prepare + bench
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario $sc --label "$lab$SFX" --prepare --warmup 5 --reps 20 ${BENCHARGS:-} "$@"; }
matrix(){ local lab=$1 sc=$2; shift 2
  log "--- label $lab$SFX (scenario $sc $*) variant order: ${ALL[*]}"; for v in "${ALL[@]}"; do B $v $lab $sc "$@"; done; }
# write cost; S4 and S1 variants print index sizes too (writecost does it for every scenario)
WC(){ local v=$1 lab=$2 sc=$3
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX"; }
wc_all(){ local lab=$1 sc=$2 v; log "--- write cost $lab$SFX (scenario $sc) order: ${ALL[*]}"; for v in "${ALL[@]}"; do WC $v $lab $sc; done; }
# S10 <variant> <scenario> <run>: fresh prepare + concurrency (keys_strict loop, 4 pollers) with plan capture; then plan check
S10(){ local v=$1 sc=$2 run=$3 p=4 lab="S10-$2-r$3$SFX" dir
  dir=$(res $v $lab)
  step "$dir/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab && bin/bench concurrency $D $(va $v) --capture-plans --scenario $sc --label $lab --pollers $p --rate 0 --seconds ${S10SECONDS:-60}"
  local f n=0 bad=0 pf ix
  for pf in "$dir"/plans-*/pages.txt "$dir"/concurrency-p$p-r0.plans.txt; do [ -e "$pf" ] || continue; n=$((n+1))
    for ix in idx_outbox_record_status_created_id_incomplete idx_outbox_record_status_retry_incomplete; do
      if grep -q "$ix" "$pf"; then bad=$((bad+1)); log "PLAN CHECK BAD: $v $lab: queue index $ix in $pf"; fi; done; done
  log "plan check $v $lab: $n plan files, queue-index-in-pages: $bad; $(grep -h 'custom_plans' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^auto_explain:' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^processed_per_s' "$dir"/concurrency-p$p-r0.txt 2>/dev/null)"; }
# s10_all <scenario>: S10RUNS (default 2) runs per variant, order rotated by 2 per run
s10_all(){ local sc=$1 r ord v
  for r in $(seq 1 ${S10RUNS:-2}); do ord=$(rot $(( 2 * (r - 1) )) "${ALL[@]}" | tr '\n' ' ')
    log "--- S10 on $sc run $r$SFX (pollers 4, ${S10SECONDS:-60} s) order: $ord"; for v in $ord; do S10 $v $sc $r; done; done; }
churn_all(){ local v; log "--- S9 churn$SFX order: ${ALL[*]}"
  for v in "${ALL[@]}"; do
    step "$(res $v S9$SFX)/timings.csv" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S9$SFX && bin/bench churn $D $(va $v) --scenario S2 --label S9$SFX --inserts 500000 && bin/bench bench $D $(va $v) --scenario S2 --label S9$SFX"; done; }

doscen(){ local sc=$1
  log "=== pass $PASS scenario $sc, free disk $(freegb) GB"; run bin/bench gen $D --scenario $sc
  case $sc in
  S1) matrix S1 S1; wc_all S1-wc S1; S10RUNS=1 s10_all S1 ;;   # smoke only
  S2) matrix S8-S2 S2 --owned mod4 --groups HOT,WRITE,MON; matrix S2 S2
      wc_all S2-wc S2; s10_all S2; churn_all ;;
  S4) matrix S8-S4 S4 --owned mod4 --groups HOT,WRITE,MON; matrix S4 S4
      wc_all S4-wc S4; s10_all S4 ;;
  S5) matrix S8-S5 S5 --owned mod4 --groups HOT,WRITE,MON; matrix S5 S5 ;;
  S6) matrix S8-S6 S6 --owned mod4 --groups HOT,WRITE,MON ;;
  S7) matrix S8-S7 S7 --owned mod4 --groups HOT,WRITE,MON ;;
  esac
}

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== round 3 validate start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}, passes: ${PASSES:-1 2}, variants: ${FWD[*]}, free disk: $(freegb) GB"
first=1
for PASS in ${PASSES:-1 2}; do
  if [ -z "$first" ]; then log "--- restart postgres between passes"; bin/db.sh stop postgres >>"$LOG" 2>&1; sleep 5; bin/db.sh start postgres >>"$LOG" 2>&1; fi
  first=
  SFX="-v$PASS$BASESFX"; if [ $((PASS % 2)) = 1 ]; then ALL=("${FWD[@]}"); else ALL=("${REV[@]}"); fi
  log "=== pass $PASS, suffix $SFX, order: ${ALL[*]}"
  for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
done
log "DONE round 3 validate"
