#!/usr/bin/env bash
# Driver: phase 5 round 1, PostgreSQL (spec: experiments/round1-spec.md). Run from anywhere:
#   nohup docs/db-postgresql/harness/bin/round1-pg.sh > /dev/null 2>&1 &        (env: SCENARIOS="S2 S4 S5 S6 S7" default, REDO=1, STEPS_ONLY=...)
# Logs: results/logs/round1-pg.log (steps, variant order per label), round1-pg.out (tool output), failures to results/logs/failures-round1.txt.
# Resumable: a step is skipped when its output file exists (bench: timings.csv, writecost.csv, churn.txt, concurrency-<tag>.txt,
# analyze.txt, equiv report) unless REDO=1. Results: results/<variant>/postgres/<label>-R1/ (suffix -R1 keeps the phase 3/4 results intact);
# E1P/E1M reference runs of v1: results/v1/postgres/E1P-<label>-R1/ and E1M-<label>-R1/ (back to back with the experiment variant,
# restricted with --only); equivalence: results/equiv/postgres/<scenario>/<a>-vs-<b>.md.
# Variants (13): main v1 E01a E01b E01c E02a E02b E02c E11 E12 E13 E14 E1Q; then E1P, E1M (own reference runs).
# Variant order is rotated per label: offset = 3 * index of the label in (S2 S8-S2 S4 S8-S4 S5 S6 S7) mod 13 (S2 starts with main,
# S8-S2 with E01b, S4 with E02b, S8-S4 with E12, S5 with E1Q, S6 with E01c, S7 with E02a); each label's order is logged.
# RUNTIME ESTIMATE (from results/logs/matrix-pg.log timestamps: per variant S2 ~2-2.7 min, S8-S2 ~2, S4 ~4-5.5, S8-S4 ~3-6, S5 ~2-4, S6 ~3,
#  S7 ~6 incl. prepare; 13 variants => ~4.5-5.5 h) + writecost 7 x ~2 min + concurrency 3 variants x 2 configs x ~2.5 min + S9 churn
#  2 x ~10 min + S10 for E14/v1 ~5 min + equivalence ~25 runs x 3-6 min (~1.5-2 h) + E1P (2 variants x 9 runs, prepare-dominated, ~40 min)
#  + E1M (2 variants x 7 runs, ~30 min) + E14 extras (generic plans, ANALYZE) ~15 min  =>  about 9-11 hours in total.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
SFX=${R1_SUFFIX:--R1}   # R1_SUFFIX/VARIANTS/BENCHARGS: smoke tests only
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$L/round1-pg.log"; }
run(){ log "RUN: $*"; "$@" >>"$L/round1-pg.out" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$L/failures-round1.txt"; fi; return $rc; }
# step <marker-file> <command...>: skip when the marker exists (unless REDO=1)
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>

# variant definitions: name -> "catalog-variant|worktree|ddl dir (under experiments, empty = worktree DDL)|queries dir (empty = none)"
declare -A DEF=(
 [main]="main|main||" [v1]="v1|v1||"
 [E01a]="main|main|E01a-status-created-main|" [E01b]="main|main|E01b-status-created-main-v1queries|E01b-status-created-main-v1queries"
 [E01c]="main|main|E01c-status-created-noinclude-main-v1queries|E01c-status-created-noinclude-main-v1queries"
 [E02a]="v1|v1|E02a-status-created-v1-nonpartial|" [E02b]="v1|v1|E02b-status-created-v1-partial|" [E02c]="v1|v1|E02c-status-created-v1-plus-partial|"
 [E11]="v1|v1|E11-ablate-v1-1a-recordkey-created|" [E12]="v1|v1|E12-ablate-v1-1b-no-id|" [E13]="v1|v1|E13-ablate-v1-2-no-queue-index|"
 [E14]="v1|v1|E14-ablate-v1-3-no-statistics|" [E1Q]="v1|v1||E1Q-v1-query-revert"
 [E1P]="v1|v1||E1P-pages-default-pg" [E1M]="v1|v1||E1M-oldest-due-order-by" )
BASE_OF(){ IFS='|' read cv wt dd qd <<<"${DEF[$1]}"; echo $cv; }
# va <variant> [out-variant-name]: harness args for a variant
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
# equivalence args for side a/b
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
ALL=(main v1 E01a E01b E01c E02a E02b E02c E11 E12 E13 E14 E1Q); [ -n "${VARIANTS:-}" ] && ALL=($VARIANTS)
LABELS=(S2 S8-S2 S4 S8-S4 S5 S6 S7)
order(){ local off=$1 i; for ((i=0;i<${#ALL[@]};i++)); do echo ${ALL[$(( (i+off) % ${#ALL[@]} ))]}; done; }
labidx(){ local i; for i in "${!LABELS[@]}"; do [ "${LABELS[$i]}" = "$1" ] && { echo $i; return; }; done; echo 0; }

# bench <variant> <label> <scenario> [extra args]  (fresh prepare, then bench)
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario $sc --label "$lab$SFX" --prepare ${BENCHARGS:-} "$@"; }
# matrix label: all 13 variants in rotated order
matrix(){ local lab=$1 sc=$2; shift 2; local off=$(( 3 * $(labidx $lab) % ${#ALL[@]} )); local ord; ord=$(order $off | tr '\n' ' ')
  log "--- label $lab (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
# E1P / E1M: reference v1 and the experiment back to back, restricted queries; label prefix tags the reference run
pair(){ local exp=$1 only=$2 lab=$3 sc=$4; shift 4; log "--- $exp pair $lab (scenario $sc $*) --only $only"
  step "$(res v1 $exp-$lab$SFX)/timings.csv" bin/bench bench $D $(va v1) --scenario $sc --label "$exp-$lab$SFX" --prepare --only $only ${BENCHARGS:-} "$@"
  step "$(res $exp $lab$SFX)/timings.csv" bin/bench bench $D $(va $exp) --scenario $sc --label "$lab$SFX" --prepare --only $only ${BENCHARGS:-} "$@"; }
# equiv <a> <b> <scenario> [extra]
EQ(){ local a=$1 b=$2 sc=$3; shift 3
  step "$R/equiv/postgres/$sc/$a-vs-$b.md" bin/bench equiv $D $(eqa $a a) $(eqa $b b) --scenario $sc "$@"; }
equiv_all(){ local sc=$1
  for v in E01a E01b E01c; do EQ $v main $sc; done
  for v in E02a E02b E02c E11 E12 E13 E14 E1Q; do EQ $v v1 $sc; done; }

bin/db.sh start postgres >>"$L/round1-pg.out" 2>&1
log "=== round 1 start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}"

doscen(){ local sc=$1
  log "=== scenario $sc"; run bin/bench gen $D --scenario $sc
  case $sc in
  S1) # smoke test only (R1_SUFFIX=-smoke VARIANTS="main E01b E02b E1Q" BENCHARGS="--warmup 1 --reps 2")
    matrix S1 S1; pair E1P pages,keys_strict S1 S1
    for v in main E02b; do step "$(res $v S2$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario S1 --label S2$SFX && bin/bench writecost $D $(va $v) --scenario S1 --label S2$SFX --n 400"; done
    step "$(res E14 S2-analyze$SFX)/analyze.txt" bash -c "bin/bench prepare $D $(va E14) --scenario S1 --label S2-analyze$SFX && bin/bench analyze $D $(va E14) --scenario S1 --label S2-analyze$SFX --n 2"
    step "$(res E14 S2-generic$SFX)/timings.csv" bin/bench bench $D $(va E14) --scenario S1 --label S2-generic$SFX --prepare --only keys_strict,pages --pg-plan-cache-mode force_generic_plan --warmup 1 --reps 2
    EQ E01b main S1; EQ E1Q v1 S1; EQ E1P v1 S1 ;;
  S2)
    matrix S2 S2; matrix S8-S2 S2 --owned mod4
    # E1P (pages,keys_strict) and E1M
    pair E1P pages,keys_strict S2 S2; pair E1P pages,keys_strict S2-b100 S2 --batch 100; pair E1P pages,keys_strict S8-S2 S2 --owned mod4
    pair E1M oldest_due_nonstrict S2 S2; pair E1M oldest_due_nonstrict S8-S2 S2 --owned mod4
    # write cost (WAL bytes per write, index count and sizes in writecost.csv + sizes-*.txt)
    for v in main v1 E01a E02a E02b E11 E13; do
      step "$(res $v S2$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S2$SFX && bin/bench writecost $D $(va $v) --scenario S2 --label S2$SFX"; done
    # concurrency S10: pollers 1 and 4, rate 0, 60 s (v1, E02b, E13); E14 only pollers 4 (v1 pollers 4 above)
    for v in v1 E02b E13; do for p in 1 4; do
      step "$(res $v S10$SFX)/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S10$SFX && bin/bench concurrency $D $(va $v) --scenario S2 --label S10$SFX --pollers $p --rate 0 --seconds 60"; done; done
    step "$(res E14 S10$SFX)/concurrency-p4-r0.txt" bash -c "bin/bench prepare $D $(va E14) --scenario S2 --label S10$SFX && bin/bench concurrency $D $(va E14) --scenario S2 --label S10$SFX --pollers 4 --rate 0 --seconds 60"
    # S9 churn (statistics only from autoanalyze): v1 and E14
    for v in v1 E14; do
      step "$(res $v S9$SFX)/timings.csv" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S9$SFX && bin/bench churn $D $(va $v) --scenario S2 --label S9$SFX --inserts 500000 && bin/bench bench $D $(va $v) --scenario S2 --label S9$SFX"; done
    # E14: generic plans (keys_strict, pages) and ANALYZE duration
    for v in v1 E14; do
      step "$(res $v S2-generic$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario S2 --label S2-generic$SFX --prepare --only keys_strict,pages --pg-plan-cache-mode force_generic_plan
      step "$(res $v S2-analyze$SFX)/analyze.txt" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S2-analyze$SFX && bin/bench analyze $D $(va $v) --scenario S2 --label S2-analyze$SFX --n 5"; done
    equiv_all S2; EQ E1P v1 S2; EQ E1M v1 S2 ;;
  S4)
    matrix S4 S4; matrix S8-S4 S4 --owned mod4
    pair E1P pages,keys_strict S4 S4; pair E1P pages,keys_strict S4-b100 S4 --batch 100; pair E1P pages,keys_strict S4-b1000 S4 --batch 1000
    pair E1M oldest_due_nonstrict S4 S4; pair E1M oldest_due_nonstrict S8-S4 S4 --owned mod4
    EQ E1P v1 S4 --batch 100 ;;
  S5)
    matrix S5 S5; pair E1P pages,keys_strict S5 S5; pair E1M oldest_due_nonstrict S5 S5
    equiv_all S5; EQ E1P v1 S5 ;;
  S6)
    matrix S6 S6; pair E1M oldest_due_nonstrict S6 S6 ;;
  S7)
    matrix S7 S7; pair E1P pages,keys_strict S7 S7; pair E1M oldest_due_nonstrict S7 S7
    for v in E02a E02b E02c; do EQ $v v1 S7; done ;;
  esac
}
for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
log "DONE round 1"
