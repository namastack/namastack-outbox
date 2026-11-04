#!/usr/bin/env bash
# Driver: phase 5 round 3, PostgreSQL, stage 2 (confirm). Spec: experiments/round3-spec.md "Stage 2: confirm". Run from anywhere:
#   nohup docs/db-sql/harness/bin/round3-confirm-pg.sh > /dev/null 2>&1 &     (env: SCENARIOS="S2 S4 S5 S6 S7" default, REDO=1, VARIANTS="..." optional, the default is the 9 variants below)
# Logs: results/logs/round3-confirm-pg.log (steps, variant order per label, pass order, plan/equivalence checks), round3-confirm-pg.out (tool output),
#   failures: results/logs/failures-round3c.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, concurrency-*.txt, equivalence report) unless REDO=1.
# Results: results/<variant>/postgres/<label>-R3C/, equivalence results/equiv/postgres/<scenario>/<variant>-vs-v1.md.
# Override inputs: experiments/R3C-<variant>/{ddl.sql,queries/}; v1c = DDL of R2-v1b + unmodified v1 catalog (no queries dir). Reference for equivalence: v1.
#
# Variants (9): v1c UABCD2 UABC-L UABC-L-NS UAB2C-L UABC2-L UABC-L-UNNS0 UA3BC-L UA3BC3D2. `pages` is the v1 `unnest` + `LATERAL` form in all; UABC-L-UNNS0 has
#   no inner ORDER BY, `OFFSET 0` and uses --pages-client-sort (client sort per key by (created_at, id)). UA3BC-L: index A3 instead of A; UA3BC3D2: A3, B1, C3, D2.
#   UABC-L-UNNS is not run (superseded by UNNS0, see experiments/R3X-screen-extension.md).
# Per scenario (gen once each, in this order):
#   S2: S8-S2 (--owned mod4, --groups HOT,WRITE,MON), S2 (all groups), write cost (UABC-L, UABC-L-NS, twice each: labels S2-wc, S2-wc2; the other DDLs were measured in the screen),
#       S9 churn (all variants, rotated), S10 (keys_strict loop, --capture-plans): pollers 4 x 4 passes, pollers 1 x 2 passes, 60 s, order rotated by 2 per pass,
#       plan check per run (pages plans in plans-*/pages.txt and in concurrency-*.plans.txt must use neither idx_outbox_record_status_created_id_incomplete nor
#       idx_outbox_record_status_retry_incomplete; the count of slow plans from the `auto_explain:` line of concurrency-*.txt is logged), pinned plan modes on S8-S2
#       (labels S8-S2-custom / S8-S2-generic, force_custom_plan / force_generic_plan, HOT,MON), equivalence vs v1.
#   S4: S8-S4, S4, write cost with index sizes on S4 (label S4-wc; v1c UABCD2 UABC-L UA3BC-L UA3BC3D2), pinned plan modes on S8-S4.   S5: S8-S5, S5, equivalence.   S6: S8-S6, S6.   S7: S8-S7, S7, equivalence.
#   Label matrices: warmup 5, reps 20, --prepare, variant order rotated by 3 * label index (logged).
#   Smoke (SCENARIOS=S1): bench label S1 for $VARIANTS, one S10 (S10SECONDS, pollers 4) for $S10V (default $VARIANTS), equivalence vs v1 for $EQV (default $VARIANTS).
# Env for smoke tests: R3C_SUFFIX (default -R3C), VARIANTS, BENCHARGS, S10SECONDS (default 60), S10SC (S10 scenario, default S2), S10V, EQV.
# RUNTIME ESTIMATE (step times from results/logs/round3-screen-pg.log and round2-pg.log; per variant, v1-class):
#   label benches, per variant: S8-S2 1.4 + S2 1.4 + S8-S4 3.0 + S4 2.9 + S8-S5 0.7 + S5 0.7 + S8-S6 1.5 + S6 1.5 + S8-S7 3.4 + S7 3.4 = 20 min  => 9 x 20 = 3.0 h
#   equivalence S2 2.1 + S5 1.0 + S7 3.4 = 6.5 min x 9 = 1.0 h;  S9 7.2 min x 9 = 1.1 h;  S10: (4 + 2) passes x 9 x 2.1 min = 1.9 h
#   pinned plan modes: 2 modes x (S8-S2 1.0 + S8-S4 2.3 min) x 9 = 1.0 h;  write cost S2 4 x 1.4 min + S4 5 x ~3 min = 0.35 h;  gens ~0.1 h
#   => about 8.5 hours in total (was 6.5 h for 7 variants). Free disk at preparation: ~24 GB; the driver aborts below 12 GB (S4/S7 gens and S7 equivalence schemas are
#   the largest). The added variants and S4-wc add no snapshots (each step re-prepares one schema), so the disk estimate is unchanged; check `df` after S4 if unsure.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LOG="$L/round3-confirm-pg.log"; OUT="$L/round3-confirm-pg.out"; FAIL="$L/failures-round3c.txt"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
SFX=${R3C_SUFFIX:--R3C}
MINGB=12
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
freegb(){ df -BG --output=avail "$R" | tail -1 | tr -dc 0-9; }
run(){ local g; g=$(freegb); if [ "$g" -lt $MINGB ]; then log "ABORT: free disk ${g} GB < ${MINGB} GB"; exit 3; fi
  log "RUN: $*"; "$@" >>"$OUT" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$FAIL"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>

# name -> "catalog-variant|worktree|ddl dir (under experiments)|queries dir ('' = none)"
declare -A DEF=( [v1c]="v1|v1|R3C-v1c|" )
V8="UABCD2 UABC-L UABC-L-NS UAB2C-L UABC2-L UABC-L-UNNS0 UA3BC-L UA3BC3D2"
for v in $V8 UABC-L-UNNS; do DEF[$v]="v1|v1|R3C-$v|R3C-$v"; done
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]:-v1|v1||}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
cs(){ [[ "$1" == UABC-L-UNNS* ]] && echo "--pages-client-sort"; }   # only variant whose pages SQL has no inner ORDER BY
rot(){ local off=$1; shift; local a=("$@") n=$# i; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }

ALL=(v1c $V8); [ -n "${VARIANTS:-}" ] && ALL=($VARIANTS)
LABELS=(S8-S2 S2 S8-S4 S4 S8-S5 S5 S8-S6 S6 S8-S7 S7 S1)
labidx(){ local i; for i in "${!LABELS[@]}"; do [ "${LABELS[$i]}" = "$1" ] && { echo $i; return; }; done; echo 0; }

# B <variant> <label> <scenario> [extra args]: fresh prepare + bench
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) $(cs $v) --scenario $sc --label "$lab$SFX" --prepare --warmup 5 --reps 20 ${BENCHARGS:-} "$@"; }
matrix(){ local lab=$1 sc=$2; shift 2; local off=$(( 3 * $(labidx $lab) % ${#ALL[@]} )); local ord; ord=$(rot $off "${ALL[@]}" | tr '\n' ' ')
  log "--- label $lab (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
# pinned plan modes: planmodes <label base> <scenario>  -> labels <base>-custom / <base>-generic, HOT,MON, owned mod4
planmodes(){ local base=$1 sc=$2 m i=0
  for m in custom generic; do
    local ord; ord=$(rot $(( 3 * i )) "${ALL[@]}" | tr '\n' ' '); i=$((i+1))
    log "--- plan mode $m on $base (scenario $sc) variant order: $ord"
    for v in $ord; do B $v "$base-$m" $sc --owned mod4 --groups HOT,MON --pg-plan-cache-mode force_${m}_plan; done
  done; }
WC(){ local v=$1 lab=$2 sc=${3:-S2} n=${4:-}
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX $n"; }
EQ(){ local v=$1 sc=$2; shift 2
  local rep="$R/equiv/postgres/$sc/$v-vs-v1.md"
  step "$rep" bin/bench equiv $D $(eqa $v a) $(eqa v1 b) $(cs $v) --scenario $sc "$@"
  if [ -e "$rep" ]; then local nf; nf=$(grep -cE '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep"); [ "$nf" -gt 0 ] && log "EQUIV FAIL: $v vs v1 on $sc ($nf lines with FAIL in $rep)" || log "equiv ok: $v vs v1 on $sc (no FAIL)"; fi; }
# S10 <variant> <pollers> <pass>: fresh prepare + concurrency with plan capture; then plan check
S10(){ local v=$1 p=$2 pass=$3 lab="S10-p$2-$3$SFX" sc=${S10SC:-S2} dir
  dir=$(res $v $lab)
  step "$dir/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab && bin/bench concurrency $D $(va $v) $(cs $v) --capture-plans --scenario $sc --label $lab --pollers $p --rate 0 --seconds ${S10SECONDS:-60}"
  # plan check: pages plans (per-poller final captures and auto_explain plans) must not scan either queue index
  local f n=0 bad=0 pf ix
  for pf in "$dir"/plans-*/pages.txt "$dir"/concurrency-p$p-r0.plans.txt; do [ -e "$pf" ] || continue; n=$((n+1))
    for ix in idx_outbox_record_status_created_id_incomplete idx_outbox_record_status_retry_incomplete; do
      if grep -q "$ix" "$pf"; then bad=$((bad+1)); log "PLAN CHECK BAD: $v $lab: queue index $ix in $pf"; fi; done; done
  log "plan check $v $lab: $n plan files, queue-index-in-pages: $bad; $(grep -h 'custom_plans' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^auto_explain:' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ')"; }

s10_all(){ local p passes pass
  for p in 4 1; do passes=4; [ $p = 1 ] && passes=2
    for pass in $(seq 1 $passes); do
      local ord; ord=$(rot $(( 2 * (pass - 1) )) "${ALL[@]}" | tr '\n' ' ')
      log "--- S10 pollers $p pass $pass order: $ord"; for v in $ord; do S10 $v $p $pass; done
    done; done; }
churn_all(){ local ord; ord=$(rot 2 "${ALL[@]}" | tr '\n' ' '); log "--- S9 churn variant order: $ord"
  for v in $ord; do
    step "$(res $v S9$SFX)/timings.csv" bash -c "bin/bench prepare $D $(va $v) --scenario S2 --label S9$SFX && bin/bench churn $D $(va $v) --scenario S2 --label S9$SFX --inserts 500000 && bin/bench bench $D $(va $v) $(cs $v) --scenario S2 --label S9$SFX"; done; }

doscen(){ local sc=$1
  log "=== scenario $sc, free disk $(freegb) GB"; run bin/bench gen $D --scenario $sc
  case $sc in
  S1) # smoke only
    matrix S1 S1; local v
    for v in ${S10V:-${ALL[*]}}; do S10 $v 4 1; done
    for v in ${EQV:-${ALL[*]}}; do EQ $v S1; done ;;
  S2)
    matrix S8-S2 S2 --owned mod4 --groups HOT,WRITE,MON; matrix S2 S2
    WC UABC-L S2-wc; WC UABC-L-NS S2-wc; WC UABC-L S2-wc2; WC UABC-L-NS S2-wc2
    churn_all; s10_all
    planmodes S8-S2 S2
    local v; for v in "${ALL[@]}"; do EQ $v S2; done ;;
  S4) matrix S8-S4 S4 --owned mod4 --groups HOT,WRITE,MON; matrix S4 S4
      local v; for v in v1c UABCD2 UABC-L UA3BC-L UA3BC3D2; do WC $v S4-wc S4; done   # index sizes and write cost on the S4 queue
      planmodes S8-S4 S4 ;;
  S5) matrix S8-S5 S5 --owned mod4 --groups HOT,WRITE,MON; matrix S5 S5
      local v; for v in "${ALL[@]}"; do EQ $v S5; done ;;
  S6) matrix S8-S6 S6 --owned mod4 --groups HOT,WRITE,MON; matrix S6 S6 ;;
  S7) matrix S8-S7 S7 --owned mod4 --groups HOT,WRITE,MON; matrix S7 S7
      local v; for v in "${ALL[@]}"; do EQ $v S7; done ;;
  esac
}

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== round 3 confirm start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}, variants: ${ALL[*]}, free disk: $(freegb) GB"
for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
log "DONE round 3 confirm"
