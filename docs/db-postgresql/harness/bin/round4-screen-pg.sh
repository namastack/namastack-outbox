#!/usr/bin/env bash
# Driver: phase 5 round 4, PostgreSQL, screen (spec: experiments/round4-spec.md "Variants" and "Screen"). Run from anywhere:
#   nohup docs/db-postgresql/harness/bin/round4-screen-pg.sh > /dev/null 2>&1 &     (env: SCENARIOS="S2 S4 S5 S6 S7" default, VARIANTS="..." optional, REDO=1)
# Logs: results/logs/round4-screen-pg.log (steps, variant order per label, plan and equivalence checks), round4-screen-pg.out (tool output),
#   failures: results/logs/failures-round4.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, concurrency-*.txt, equivalence report) unless REDO=1.
# Results: results/<variant>/postgres/<label>-R4/, equivalence results/equiv/postgres/<scenario>/<variant>-vs-v1.md.
# Override inputs: experiments/R4-<variant>/{ddl.sql,queries/} (base: experiments/R3C-UABC-L). Reference for equivalence: v1 (unmodified catalog, worktree DDL).
#
# Variants (8): UABC-L (reference) E08a E08A K3 D3 E07 E07b E40. `pages` keeps the `unnest` + `LATERAL` form with its inner ORDER BY in all (no --pages-client-sort).
# Screen (HOT,MON, warmup 5, reps 20, --prepare, variant order rotated by 3 * label index and logged) for the 7 variants without E40:
#   S2: gen, S8-S2.  write cost S2-wc (UABC-L E08a D3 E07 E07b), S2-wc2 (UABC-L E07 E07b, reversed order; E08A and K3 have the reference DDL),
#       equivalence vs v1 for E08a E08A K3 (K3: exit 2 / FAIL expected, not written to the failures file).
#   S4: gen, S8-S4, S16-S4 (16 partitions 0,16,...,240), write cost S4-wc / S4-wc2 (as above), S9 churn on S4 (UABC-L E07 E07b; churn 500000 inserts, no VACUUM,
#       then bench HOT,MON), S10 on S4 (4 pollers, 60 s, 2 runs per variant, order rotated by 1 per run, fresh prepare, --capture-plans, plan check as in the confirm driver).
#   S5: gen, S8-S5, equivalence.   S6: gen, S8-S6.   S7: gen, S8-S7, equivalence.
# E40 (UUIDv7 ids) has its own snapshot: HARNESS_ID_VERSION=7 makes `bench gen` emit UUIDv7 ids (48-bit ms of created_at, 12-bit counter, random tail) and the
#   insert statements of writecost/concurrency/churn do the same. After the v4 steps of S2 and of S4 the driver generates a v7 snapshot of that scenario and runs the E40
#   steps there (S2-wc, S2-wc2; S4-wc, S4-wc2, S9, S10 runs 1 and 2); the next scenario regenerates a v4 snapshot. E40 steps therefore always run after the reference steps
#   of the same group (order effect of at most one position; no read matrix for E40: the SQL is the reference's).
# Smoke (SCENARIOS=S1): the S2 and S4 parts run on S1 with labels S8-S1, S16-S1, S1-wc, S1-wc2, S9, S10-S1-r<run>; equivalence on S1.
# Env for smoke tests: R4_SUFFIX (default -R4), VARIANTS, BENCHARGS (appended to every bench), WCARGS (writecost, e.g. "--n 500"), CHURNN (churn inserts, default 500000),
#   S10SECONDS (default 60), S10RUNS (default 2), MINGB.
# RUNTIME ESTIMATE (mean step times of results/logs/round3-validate-pg.log and round3-screenx-pg.log; per step incl. prepare):
#   S2: gen 0.2 + S8-S2 1.1 x 7 + wc 1.3 x 5 + wc2 1.3 x 3 + equiv S2 2.1 x 3 + v7 gen 0.2 + E40 wc 1.3 x 2                                      = 28 min
#   S4: gen 0.3 + S8-S4 2.4 x 7 + S16-S4 2.2 x 7 + wc 2.6 x 5 + wc2 2.6 x 3 + S9 on S4 ~9 x 3 (S2 churn 6.6; S4 unmeasured) + S10 on S4 3.2 x 4 x 2 + v7 gen 0.3
#       + E40: wc 2.6 x 2 + S9 9 + S10 3.2 x 2                                                                                                           = 133 min
#   S5: S8-S5 0.7 x 7 + equiv 1.0 x 3 = 8 min;  S6: S8-S6 1.2 x 7 = 8 min;  S7: S8-S7 1.3 x 7 + equiv 3.4 x 3 = 19 min;  gens 1 min
#   => about 3.3 hours in total (E08 steps may be slower than v1-class steps: +/- 20 %). Free disk at preparation ~20 GB; aborts below 12 GB (S4 gen and S7 equivalence
#   schemas are the largest; a v7 gen replaces the snapshot, it does not add one).
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LOG="$L/round4-screen-pg.log"; OUT="$L/round4-screen-pg.out"; FAIL="$L/failures-round4.txt"
WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
SFX=${R4_SUFFIX:--R4}
MINGB=${MINGB:-12}
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
freegb(){ df -BG --output=avail "$R" | tail -1 | tr -dc 0-9; }
run(){ local g; g=$(freegb); if [ "$g" -lt "$MINGB" ]; then log "ABORT: free disk ${g} GB < ${MINGB} GB"; echo "ABORT: free disk ${g} GB" >> "$FAIL"; exit 3; fi
  log "RUN: $*"; "$@" >>"$OUT" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then
    if [ -n "${EXPECT_RC:-}" ] && [ "$rc" = "$EXPECT_RC" ]; then log "exit rc=$rc (expected): $*"
    else log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$FAIL"; fi
  fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>
v7on(){ export HARNESS_ID_VERSION=7; }
v7off(){ unset HARNESS_ID_VERSION; }

# name -> "catalog-variant|worktree|ddl dir (under experiments)|queries dir"
declare -A DEF=()
for v in UABC-L E08a E08A K3 D3 E07 E07b E40; do DEF[$v]="v1|v1|R4-$v|R4-$v"; done
WT_v1=/home/zamo/git/outbox-wt-v1
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]:-v1|v1||}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }

ALL=(UABC-L E08a E08A K3 D3 E07 E07b E40); [ -n "${VARIANTS:-}" ] && ALL=($VARIANTS)
has(){ [[ " ${ALL[*]} " == *" $1 "* ]]; }
MV=(); for v in "${ALL[@]}"; do [ "$v" != E40 ] && MV+=($v); done      # read matrix: everything but E40
pick(){ local v; for v in "$@"; do has $v && echo $v; done; }          # keep the given variants that are selected
LABELS=(S8-S2 S8-S4 S8-S5 S8-S6 S8-S7 S16-S4)
labidx(){ local i; for i in "${!LABELS[@]}"; do [ "${LABELS[$i]}" = "$1" ] && { echo $i; return; }; done; echo 0; }
OWN16=$(seq -s, 0 16 240)
CHURNN=${CHURNN:-500000}

# B <variant> <label> <scenario> [extra args]: fresh prepare + bench
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario $sc --label "$lab$SFX" --prepare --groups HOT,MON --warmup 5 --reps 20 ${BENCHARGS:-} "$@"; }
matrix(){ local lab=$1 sc=$2; shift 2; [ ${#MV[@]} -eq 0 ] && return 0
  local off=$(( 3 * $(labidx $lab) % ${#MV[@]} )); local ord; ord=$(rot $off "${MV[@]}" | tr '\n' ' ')
  log "--- label $lab$SFX (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
WC(){ local v=$1 lab=$2 sc=$3
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX ${WCARGS:-}"; }
# write cost, distinct DDLs: <lab> (UABC-L E08a D3 E07 E07b) and <lab>2 (UABC-L E07 E07b, reversed); E40 is done on its own snapshot
wc_base(){ local sc=$1 lab=$2 v ord
  ord=$(pick UABC-L E08a D3 E07 E07b | tr '\n' ' '); log "--- write cost $lab$SFX (scenario $sc) order: $ord"; for v in $ord; do WC $v $lab $sc; done
  ord=$(pick E07b E07 UABC-L | tr '\n' ' ');        log "--- write cost ${lab}2$SFX (scenario $sc) order: $ord"; for v in $ord; do WC $v ${lab}2 $sc; done; }
EQ(){ local v=$1 sc=$2; shift 2
  local rep="$R/equiv/postgres/$sc/$v-vs-v1.md"
  [ "$v" = K3 ] && export EXPECT_RC=2
  step "$rep" bin/bench equiv $D $(eqa $v a) $(eqa v1 b) --scenario $sc "$@"; unset EXPECT_RC
  if [ -e "$rep" ]; then local nf; nf=$(grep -cE '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep"); [ "$nf" -gt 0 ] && log "EQUIV FAIL: $v vs v1 on $sc ($nf lines with FAIL in $rep)$([ $v = K3 ] && echo ' (expected for K3)')" || log "equiv ok: $v vs v1 on $sc (no FAIL)"; fi; }
equiv_all(){ local sc=$1 v; for v in $(pick E08a E08A K3); do EQ $v $sc; done; }
# S9 <variant> <scenario>: fresh prepare, churn (no manual VACUUM), bench
S9(){ local v=$1 sc=$2
  step "$(res $v S9$SFX)/timings.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label S9$SFX && bin/bench churn $D $(va $v) --scenario $sc --label S9$SFX --inserts $CHURNN && bin/bench bench $D $(va $v) --scenario $sc --label S9$SFX --groups HOT,MON ${BENCHARGS:-}"; }
# S10 <variant> <scenario> <run>: fresh prepare + concurrency (keys_strict loop, 4 pollers) with plan capture; then the plan check of the confirm driver
S10(){ local v=$1 sc=$2 run=$3 p=4 lab="S10-$2-r$3$SFX" dir
  dir=$(res $v $lab)
  step "$dir/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab && bin/bench concurrency $D $(va $v) --capture-plans --scenario $sc --label $lab --pollers $p --rate 0 --seconds ${S10SECONDS:-60}"
  # pages plans (per-poller final captures and auto_explain plans) must not scan a queue index
  local n=0 bad=0 pf ix
  for pf in "$dir"/plans-*/pages.txt "$dir"/concurrency-p$p-r0.plans.txt; do [ -e "$pf" ] || continue; n=$((n+1))
    for ix in idx_outbox_record_status_created_id_incomplete idx_outbox_record_status_retry_incomplete idx_outbox_record_part_status_created_id_incomplete; do
      if grep -q "$ix" "$pf"; then bad=$((bad+1)); log "PLAN CHECK BAD: $v $lab: queue index $ix in $pf"; fi; done; done
  log "plan check $v $lab: $n plan files, queue-index-in-pages: $bad; $(grep -h 'custom_plans' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^auto_explain:' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^processed_per_s' "$dir"/concurrency-p$p-r0.txt 2>/dev/null)"; }
# churn_s10 <scenario> <variants...>: S9 for the variants, then S10RUNS runs per variant (order rotated by 1 per run)
churn_s10(){ local sc=$1; shift; local vs=("$@") r ord v; [ ${#vs[@]} -eq 0 ] && return 0
  log "--- S9 churn on $sc$SFX order: ${vs[*]}"; for v in "${vs[@]}"; do S9 $v $sc; done
  for r in $(seq 1 ${S10RUNS:-2}); do ord=$(rot $(( r - 1 )) "${vs[@]}" | tr '\n' ' ')
    log "--- S10 on $sc run $r$SFX (pollers 4, ${S10SECONDS:-60} s) order: $ord"; for v in $ord; do S10 $v $sc $r; done; done; }

# parts shared by S2 (write cost, equivalence) and S4 (write cost, S9, S10)
part_s2(){ local sc=$1 lab=$2   # lab = label base for write cost (S2-wc)
  matrix S8-$sc $sc --owned mod4
  wc_base $sc $lab; equiv_all $sc; }
part_s4(){ local sc=$1 lab=$2
  matrix S8-$sc $sc --owned mod4; matrix S16-$sc $sc --owned $OWN16
  wc_base $sc $lab
  churn_s10 $sc $(pick UABC-L E07 E07b); }
part_e40(){ local sc=$1 lab=$2 withchurn=$3
  has E40 || return 0
  log "--- E40: UUIDv7 snapshot of $sc"; v7on; run bin/bench gen $D --scenario $sc
  log "--- write cost $lab$SFX and ${lab}2$SFX (E40, scenario $sc)"; WC E40 $lab $sc; WC E40 ${lab}2 $sc
  [ "$withchurn" = 1 ] && churn_s10 $sc E40
  v7off; }

doscen(){ local sc=$1
  log "=== scenario $sc, free disk $(freegb) GB"; v7off; run bin/bench gen $D --scenario $sc
  case $sc in
  S1) # smoke only: the S2 and S4 parts on S1
    matrix S8-S1 S1 --owned mod4; matrix S16-S1 S1 --owned $OWN16
    wc_base S1 S1-wc; equiv_all S1; churn_s10 S1 $(pick UABC-L E07 E07b); part_e40 S1 S1-wc 1 ;;
  S2) part_s2 S2 S2-wc; part_e40 S2 S2-wc 0 ;;
  S4) part_s4 S4 S4-wc; part_e40 S4 S4-wc 1 ;;
  S5) matrix S8-S5 S5 --owned mod4; equiv_all S5 ;;
  S6) matrix S8-S6 S6 --owned mod4 ;;
  S7) matrix S8-S7 S7 --owned mod4; equiv_all S7 ;;
  esac
  v7off
}

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== round 4 screen start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}, variants: ${ALL[*]}, suffix $SFX, free disk: $(freegb) GB"
for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
log "DONE round 4 screen"
