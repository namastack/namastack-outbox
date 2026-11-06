#!/usr/bin/env bash
# Driver: phase 5 round 2, PostgreSQL (spec: experiments/round2-spec.md). Run from anywhere:
#   nohup docs/db-postgresql/harness/bin/round2-pg.sh > /dev/null 2>&1 &        (env: SCENARIOS="S2 S4 S5 S6 S7" default, REDO=1)
# Logs: results/logs/round2-pg.log (steps, variant order per label), round2-pg.out (tool output), failures: results/logs/failures-round2.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, churn/timings.csv, concurrency-p<N>-r0.txt, equiv report)
# unless REDO=1. Results: results/<variant>/postgres/<label>-R2/; equivalence results/equiv/postgres/<scenario>/<a>-vs-<b>.md.
# Variants (17): main v1 v1b A1 A2 A5 A6 K12 E04 MIN E21 E22 E22b E23 E24 E26 E28 (E24/E26/E28 SQL-only: --only <overridden ids>,keys_strict).
# Rotation: variant order per label starts at offset 3 * index of the label in (S2 S8-S2 S4 S8-S4 S5 S6 S7) mod 17; each order is logged.
# Per scenario block (after the 17-variant matrices): S2: writecost (14 DDL variants + repeat for v1 v1b MIN, label S2-wc2), S9 churn (v1 v1b A6 E04 MIN),
#  S10 concurrency pollers 1+4 (v1 v1b A6 E04 MIN; pass 1 in that order, pass 2 reverse, label S10b), plan-mode runs S2/S4/S7 for v1 v1b
#  (labels <S>-custom-R2, <S>-generic-R2; default mode = the matrix run, whose plancache.csv records custom/generic plan use of the timed reps),
#  equivalence S2, S5, S7: v1b A1 A2 A5 A6 K12 E04 MIN vs v1, E21 E22 E22b E23 E24 E26 E28 vs main.
# RUNTIME ESTIMATE (from results/logs/round1-pg.log step durations, per variant: S2 2.3 min, S8-S2 2.2, S4 4.3, S8-S4 3.7, S5 1.5, S6 1.7, S7 2.9 =
#  ~18.6 min): 14 full variants ~4.3 h + 3 SQL-only variants ~0.5 h + writecost 17 x ~2 min (0.6 h) + S9 5 x 10 min (0.85 h) + S10 20 x 2.5 min (0.85 h)
#  + plan modes 12 x ~3 min (0.6 h) + equivalence 3 scenarios x 15 runs x ~3 min (2.3 h) + 3 gens  =>  about 10 hours in total.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
SFX=${R2_SUFFIX:--R2}   # R2_SUFFIX/VARIANTS/BENCHARGS: smoke tests only
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$L/round2-pg.log"; }
run(){ log "RUN: $*"; "$@" >>"$L/round2-pg.out" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$L/failures-round2.txt"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>

# name -> "catalog-variant|worktree|ddl dir (under experiments, empty = worktree DDL)|queries dir (empty = none)"
declare -A DEF=(
 [main]="main|main||" [v1]="v1|v1||"
 [v1b]="v1|v1|R2-v1b|R2-v1b" [A1]="v1|v1|R2-A1|R2-A1" [A2]="v1|v1|R2-A2|R2-A2" [A5]="v1|v1|R2-A5|R2-A5" [A6]="v1|v1|R2-A6|R2-A6"
 [K12]="v1|v1|R2-K12|R2-K12" [E04]="v1|v1|R2-E04|R2-E04" [MIN]="v1|v1|R2-MIN|R2-MIN"
 [E21]="main|main|R2-E21|" [E22]="main|main|R2-E22|" [E22b]="main|main|R2-E22b|" [E23]="main|main|R2-E23|"
 [E24]="main|main||R2-E24" [E26]="main|main||R2-E26" [E28]="main|main||R2-E28" )
# SQL-only variants: restricted to overridden ids plus keys_strict as control
declare -A ONLY=( [E24]="keys_strict,keys_nonstrict" [E26]="oldest_created,oldest_due_strict,keys_strict" [E28]="pages,keys_strict" )
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
ALL=(main v1 v1b A1 A2 A5 A6 K12 E04 MIN E21 E22 E22b E23 E24 E26 E28); [ -n "${VARIANTS:-}" ] && ALL=($VARIANTS)
LABELS=(S2 S8-S2 S4 S8-S4 S5 S6 S7)
order(){ local off=$1 i; for ((i=0;i<${#ALL[@]};i++)); do echo ${ALL[$(( (i+off) % ${#ALL[@]} ))]}; done; }
labidx(){ local i; for i in "${!LABELS[@]}"; do [ "${LABELS[$i]}" = "$1" ] && { echo $i; return; }; done; echo 0; }

# B <variant> <label> <scenario> [extra args]: fresh prepare, then bench (SQL-only variants get --only)
B(){ local v=$1 lab=$2 sc=$3; shift 3; local only=""; [ -n "${ONLY[$v]:-}" ] && only="--only ${ONLY[$v]}"
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario $sc --label "$lab$SFX" --prepare $only ${BENCHARGS:-} "$@"; }
matrix(){ local lab=$1 sc=$2; shift 2; local off=$(( 3 * $(labidx $lab) % ${#ALL[@]} )); local ord; ord=$(order $off | tr '\n' ' ')
  log "--- label $lab (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
EQ(){ local a=$1 b=$2 sc=$3; shift 3
  step "$R/equiv/postgres/$sc/$a-vs-$b.md" bin/bench equiv $D $(eqa $a a) $(eqa $b b) --scenario $sc "$@"; }
equiv_all(){ local sc=$1 v
  for v in v1b A1 A2 A5 A6 K12 E04 MIN; do EQ $v v1 $sc; done
  for v in E21 E22 E22b E23 E24 E26 E28; do EQ $v main $sc; done; }
# plan modes for v1 and v1b: labels <S>-custom, <S>-generic
planmodes(){ local sc=$1 m v
  for m in custom generic; do for v in v1 v1b; do
    step "$(res $v $sc-$m$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario ${SCEN_OF:-$sc} --label "$sc-$m$SFX" --prepare --pg-plan-cache-mode force_${m}_plan ${BENCHARGS:-}; done; done; }
WC(){ local v=$1 lab=$2 sc=${3:-S2} n=${4:-}   # writecost on fresh prepare
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX $n"; }

bin/db.sh start postgres >>"$L/round2-pg.log" 2>&1
log "=== round 2 start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}"

doscen(){ local sc=$1
  log "=== scenario $sc"; run bin/bench gen $D --scenario $sc
  case $sc in
  S1) # smoke test only: R2_SUFFIX=-smoke VARIANTS="v1 v1b A6 MIN E04 E24" BENCHARGS="--warmup 1 --reps 2" SCENARIOS=S1
    matrix S2 S1
    WC v1b S2 S1 "--n 400"; WC MIN S2 S1 "--n 400"
    EQ MIN v1 S1; EQ E04 v1 S1; EQ E24 main S1
    SCEN_OF=S1 planmodes S1 ;;
  S2)
    matrix S2 S2; matrix S8-S2 S2 --owned mod4
    # write cost on S2 (WAL bytes per write, index count and sizes), then repeat for v1 v1b MIN (noise estimate)
    for v in main v1 v1b A1 A2 A5 A6 K12 E04 MIN E21 E22 E22b E23; do WC $v S2; done
    for v in v1 v1b MIN; do WC $v S2-wc2; done
    # S9 churn (no manual VACUUM/ANALYZE)
    for v in v1 v1b A6 E04 MIN; do
      step "$(res $v S9$SFX)/timings.csv" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S9$SFX && bin/bench churn $D $(va $v) --scenario S2 --label S9$SFX --inserts 500000 && bin/bench bench $D $(va $v) --scenario S2 --label S9$SFX"; done
    # S10 concurrency: pollers 1 and 4, rate 0, 60 s; pass 2 (label S10b) in reverse variant order
    for v in v1 v1b A6 E04 MIN; do for p in 1 4; do
      step "$(res $v S10$SFX)/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S10$SFX && bin/bench concurrency $D $(va $v) --scenario S2 --label S10$SFX --pollers $p --rate 0 --seconds 60"; done; done
    for v in MIN E04 A6 v1b v1; do for p in 1 4; do
      step "$(res $v S10b$SFX)/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S10b$SFX && bin/bench concurrency $D $(va $v) --scenario S2 --label S10b$SFX --pollers $p --rate 0 --seconds 60"; done; done
    SCEN_OF=S2 planmodes S2
    equiv_all S2 ;;
  S4)
    matrix S4 S4; matrix S8-S4 S4 --owned mod4; planmodes S4 ;;
  S5)
    matrix S5 S5; equiv_all S5 ;;
  S6)
    matrix S6 S6 ;;
  S7)
    matrix S7 S7; planmodes S7; equiv_all S7 ;;
  esac
}
for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
log "DONE round 2"
