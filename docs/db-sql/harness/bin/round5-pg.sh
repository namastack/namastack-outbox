#!/usr/bin/env bash
# Driver: phase 5 round 5, PostgreSQL (spec: experiments/round5-spec.md). Protocol as round3-validate-pg.sh (two passes, reversed order, restart between passes).
#   nohup docs/db-sql/harness/bin/round5-pg.sh > /dev/null 2>&1 &     (env: SCENARIOS="S2 S4 S5 S6 S7" default, PASSES="1 2", VARIANTS="..." optional, REDO=1)
# Logs: results/logs/round5-pg.log (steps, orders, plan and equivalence checks), round5-pg.out (tool output), failures: results/logs/failures-round5.txt.
# Resumable: a step is skipped when its output file exists (timings.csv, writecost.csv, concurrency-*.txt, equivalence report) unless REDO=1. Restart after an abort: the same command.
# Results: results/<variant>/postgres/<label>-v<pass>-R5/ (suffix -R5), equivalence results/equiv/postgres/<scenario>/<variant>-vs-v1.md.
# Override inputs: experiments/R5-<variant>/{ddl.sql,queries/}; catalog v1, worktree outbox-wt-v1. Reference for equivalence: unmodified v1 (catalog + worktree DDL).
#
# Variants (7): UABC-L P-IN P-UNO OD-OB UABCD-L D3 E40. Pass 1 in that order, pass 2 reversed (E40 first); postgres is restarted between the passes.
#   Every label of a pass uses the pass order (restricted to the variants that run in that label); S10 runs rotate that order by 2 per run.
# Matrix per pass (label suffix -v<pass>-R5, warmup 5, reps 20, --prepare):
#   full (UABC-L UABCD-L D3 E40): S2: S8-S2 (--owned mod4, groups HOT,WRITE,MON), S2 (all groups), S2-wc, S10 on S2 (2 runs), S9 (churn on S2);
#     S4: S8-S4, S4 (all groups, so count_statuses_all), S4-wc, S10 on S4 (2 runs); S5: S8-S5, S5; S6: S8-S6; S7: S8-S7.
#   P-IN P-UNO: --only pages,keys_strict on S8-S2 S8-S4 S8-S5 S8-S6 S8-S7 S5; S10 on S2 and S4 (2 runs each; keys_strict loop + pages, 4 pollers, 60 s, --capture-plans).
#   OD-OB: --only oldest_due_nonstrict,keys_strict on S8-S2 S8-S4 S8-S5 S8-S6 S8-S7 S4 S5 (plancache.csv is written by every bench step).
#   S10: fresh prepare, concurrency with 4 pollers, 60 s; plan check: pages plans (per-poller final captures, auto_explain plans) must use neither
#     idx_outbox_record_status_created_id_incomplete nor idx_outbox_record_status_retry_incomplete; the log line has the count of slow plans (auto_explain line),
#     custom/generic plan counts and the number of BitmapAnd in pages plans.
# E40 (UUIDv7): HARNESS_ID_VERSION=7 makes `bench gen` emit v7 ids and the inserts of writecost/concurrency/churn do the same. Per scenario and pass the driver runs two
#   phases: v4 phase (gen of the v4 snapshot, all variants except E40) and v7 phase (gen of the v7 snapshot, E40 only). Pass 1: v4 phase first; pass 2 (reversed order):
#   v7 phase first. The snapshot of the next phase replaces the previous one, so no variant reads data of the other id version. After each gen the id version of the
#   snapshot is logged (snapcheck). The v7 snapshots of S5, S6, S7 are new (round 4 did S2 and S4 only).
# Equivalence vs v1 on S2, S5, S7 (v4 phase, once; skipped when the report exists) for P-IN P-UNO OD-OB UABCD-L. Not for E40 (ids differ by construction; its SQL is the
#   reference's) and not for D3 (queries byte-identical to UABCD-L and UABC-L, only the DDL differs, so the result sets are those of UABCD-L). OD-OB: an empty result is zero
#   rows where MIN gives one NULL row; a FAIL for that is NOT hidden: the run is logged as FAILED (failures-round5.txt) and "EQUIV FAIL" with the report lines.
# Smoke (SCENARIOS=S1): S8-S1, S1, S1-wc, S10 on S1 (S10RUNS, default 2), S9 on S1, equivalence on S1. Env for smoke: R5_SUFFIX (default -R5), R5_LOGX (log file suffix),
#   VARIANTS, PASSES, BENCHARGS (appended to every bench), WCARGS (writecost, e.g. "--n 500"), CHURNN (default 500000), S10SECONDS (60), S10RUNS (2), MINGB (12).
# RUNTIME ESTIMATE (minutes per step, from results/logs/round3-validate-pg.log and round4-screen-pg.log; S4 reads taken from round 4: S8-S4 ~6 min per variant, S4 label ~8):
#   full variant per pass: S2: S8-S2 1.1 + S2 1.5 + wc 1.3 + S10 2 x 1.95 + S9 6.6 = 14.4;  S4: S8-S4 6 + S4 8 + wc 2.7 + S10 2 x 3.3 = 23.3;  S5 1.5, S6 1.2, S7 1.3 -> 42 min, x 4 = 167 min
#   P-IN, P-UNO (restricted reads, prepare dominates): 8 + S10 on S2 and S4 10.5 = ~19 min each = 37 min;  OD-OB: ~12 min;  gens (10 per pass) ~3 min  => ~220 min = 3.7 h per pass
#   two passes 7.3 h + equivalence once (4 variants x (S2 2.2 + S5 1.0 + S7 3.4) = 26 min) + restart => about 7.8 hours in total (range 7-9; S4 steps dominate: ~45 %).
#   Disk: ~20 GB free at preparation (24 GB when the smoke ended); aborts below 12 GB. A gen replaces the snapshot; the v7/v4 alternation adds 10 gens per pass.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LX=${R5_LOGX:-}
LOG="$L/round5-pg$LX.log"; OUT="$L/round5-pg$LX.out"; FAIL="$L/failures-round5$LX.txt"
WT_v1=/home/zamo/git/outbox-wt-v1
D="--dialect postgres"
BASESFX=${R5_SUFFIX:--R5}
SFX=$BASESFX
MINGB=${MINGB:-12}
CHURNN=${CHURNN:-500000}
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
freegb(){ df -BG --output=avail "$R" | tail -1 | tr -dc 0-9; }
run(){ local g; g=$(freegb); if [ "$g" -lt "$MINGB" ]; then log "ABORT: free disk ${g} GB < ${MINGB} GB"; echo "ABORT: free disk ${g} GB" >> "$FAIL"; exit 3; fi
  log "RUN: $*"; "$@" >>"$OUT" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$FAIL"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }          # res <variant> <label>
v7on(){ export HARNESS_ID_VERSION=7; }
v7off(){ unset HARNESS_ID_VERSION; }

# every variant: catalog v1, worktree v1, DDL and queries from experiments/R5-<variant>
declare -A DEF=()
for v in UABC-L P-IN P-UNO OD-OB UABCD-L D3 E40; do DEF[$v]="v1|v1|R5-$v|R5-$v"; done
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]:-v1|v1||}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; [ $n -eq 0 ] && return 0; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }
# restricted runs: extra bench args per variant
onlyfor(){ case $1 in P-IN|P-UNO) echo "--only pages,keys_strict";; OD-OB) echo "--only oldest_due_nonstrict,keys_strict";; esac; }

FWD=(UABC-L P-IN P-UNO OD-OB UABCD-L D3 E40); [ -n "${VARIANTS:-}" ] && FWD=($VARIANTS)
REV=(); for ((i=${#FWD[@]}-1;i>=0;i--)); do REV+=(${FWD[$i]}); done
ALL=("${FWD[@]}"); PHASE=v4
# pick <variants...>: those of the list that are selected, in pass order, belonging to the current snapshot phase (v7: E40 only; v4: all but E40)
pick(){ local v w; for v in "${ALL[@]}"; do for w in "$@"; do
    [ "$v" = "$w" ] || continue
    if [ "$PHASE" = v7 ]; then [ "$v" = E40 ] && echo $v; else [ "$v" != E40 ] && echo $v; fi; done; done; }
FULLV="UABC-L UABCD-L D3 E40"; RESTR_P="P-IN P-UNO"; RESTR_O="OD-OB"

# B <variant> <label> <scenario> [extra args]: fresh prepare + bench; restricted variants get their --only list
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario $sc --label "$lab$SFX" --prepare --warmup 5 --reps 20 ${BENCHARGS:-} "$@" $(onlyfor $v); }
# matrix <label> <scenario> "<variants>" [extra args]
matrix(){ local lab=$1 sc=$2 vs=$3; shift 3; local ord; ord=$(pick $vs | tr '\n' ' '); [ -z "${ord// /}" ] && return 0
  log "--- label $lab$SFX (scenario $sc $*) variant order: $ord"; for v in $ord; do B $v $lab $sc "$@"; done; }
WC(){ local v=$1 lab=$2 sc=$3
  step "$(res $v $lab$SFX)/writecost.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab$SFX && bin/bench writecost $D $(va $v) --scenario $sc --label $lab$SFX ${WCARGS:-}"; }
wc_all(){ local lab=$1 sc=$2 ord v; ord=$(pick $FULLV | tr '\n' ' '); [ -z "${ord// /}" ] && return 0
  log "--- write cost $lab$SFX (scenario $sc) order: $ord"; for v in $ord; do WC $v $lab $sc; done; }
# S10 <variant> <scenario> <run>: fresh prepare + concurrency (keys_strict loop, 4 pollers) with plan capture; then the plan check
S10(){ local v=$1 sc=$2 run=$3 p=4 lab="S10-$2-r$3$SFX" dir
  dir=$(res $v $lab)
  step "$dir/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab && bin/bench concurrency $D $(va $v) --capture-plans --scenario $sc --label $lab --pollers $p --rate 0 --seconds ${S10SECONDS:-60}"
  local n=0 bad=0 ba=0 pf ix
  for pf in "$dir"/plans-*/pages.txt "$dir"/concurrency-p$p-r0.plans.txt; do [ -e "$pf" ] || continue; n=$((n+1))
    ba=$((ba + $(grep -c 'BitmapAnd' "$pf")))
    for ix in idx_outbox_record_status_created_id_incomplete idx_outbox_record_status_retry_incomplete; do
      if grep -q "$ix" "$pf"; then bad=$((bad+1)); log "PLAN CHECK BAD: $v $lab: queue index $ix in $pf"; fi; done; done
  log "plan check $v $lab: $n plan files, queue-index-in-pages: $bad, BitmapAnd lines: $ba; $(grep -h 'custom_plans' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^auto_explain:' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | head -4 | tr '\n' ' ') $(grep -h '^processed_per_s' "$dir"/concurrency-p$p-r0.txt 2>/dev/null)"; }
# s10_all <scenario> "<variants>": S10RUNS (default 2) runs per variant, order rotated by 2 per run
s10_all(){ local sc=$1 vs=$2 r ord v; local base; base=($(pick $vs)); [ ${#base[@]} -eq 0 ] && return 0
  for r in $(seq 1 ${S10RUNS:-2}); do ord=$(rot $(( 2 * (r - 1) )) "${base[@]}" | tr '\n' ' ')
    log "--- S10 on $sc run $r$SFX (pollers 4, ${S10SECONDS:-60} s) order: $ord"; for v in $ord; do S10 $v $sc $r; done; done; }
churn_all(){ local sc=$1 ord v; ord=$(pick $FULLV | tr '\n' ' '); [ -z "${ord// /}" ] && return 0; log "--- S9 churn on $sc$SFX order: $ord"
  for v in $ord; do
    step "$(res $v S9$SFX)/timings.csv" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label S9$SFX && bin/bench churn $D $(va $v) --scenario $sc --label S9$SFX --inserts $CHURNN && bin/bench bench $D $(va $v) --scenario $sc --label S9$SFX ${BENCHARGS:-}"; done; }
# equivalence vs v1 (v4 phase only), once per variant and scenario
EQ(){ local v=$1 sc=$2; local rep="$R/equiv/postgres/$sc/$v-vs-v1.md"
  step "$rep" bin/bench equiv $D $(eqa $v a) $(eqa v1 b) --scenario $sc
  if [ -e "$rep" ]; then local nf; nf=$(grep -cE '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep")
    if [ "$nf" -gt 0 ]; then log "EQUIV FAIL: $v vs v1 on $sc ($nf lines with FAIL/ERROR in $rep): $(grep -E '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep" | cut -c1-300 | tr '\n' ' ')"
    else log "equiv ok: $v vs v1 on $sc (no FAIL)"; fi; fi; }
equiv_all(){ local sc=$1 v; [ "$PHASE" = v4 ] || return 0; for v in $(pick P-IN P-UNO OD-OB UABCD-L); do EQ $v $sc; done; }
snapcheck(){ local f=build/snap-postgres.txt
  log "snapcheck: $(tr '\n' ' ' < $f 2>/dev/null) ids(version nibble of 2000 snapshot ids; 4=v4, 7=v7)=$(docker exec outbox-bench-postgres psql -U bench -d bench -Atc "SELECT string_agg(DISTINCT substr(id,15,1), ',') FROM (SELECT id FROM snap.rec LIMIT 2000) s" 2>&1 | head -1)"; }

ALLS="UABC-L P-IN P-UNO OD-OB UABCD-L D3 E40"
body(){ local sc=$1
  case $sc in
  S1) # smoke only
    matrix S8-S1 S1 "$ALLS" --owned mod4 --groups HOT,WRITE,MON; matrix S1 S1 "$FULLV $RESTR_P $RESTR_O"
    wc_all S1-wc S1; s10_all S1 "$FULLV $RESTR_P"; churn_all S1; equiv_all S1 ;;
  S2) matrix S8-S2 S2 "$ALLS" --owned mod4 --groups HOT,WRITE,MON; matrix S2 S2 "$FULLV"
      wc_all S2-wc S2; s10_all S2 "$FULLV $RESTR_P"; churn_all S2; equiv_all S2 ;;
  S4) matrix S8-S4 S4 "$ALLS" --owned mod4 --groups HOT,WRITE,MON; matrix S4 S4 "$FULLV $RESTR_O"
      wc_all S4-wc S4; s10_all S4 "$FULLV $RESTR_P" ;;
  S5) matrix S8-S5 S5 "$ALLS" --owned mod4 --groups HOT,WRITE,MON; matrix S5 S5 "$FULLV $RESTR_P $RESTR_O"; equiv_all S5 ;;
  S6) matrix S8-S6 S6 "$ALLS" --owned mod4 --groups HOT,WRITE,MON ;;
  S7) matrix S8-S7 S7 "$ALLS" --owned mod4 --groups HOT,WRITE,MON; equiv_all S7 ;;
  esac; }

doscen(){ local sc=$1 ph phases
  log "=== pass $PASS scenario $sc, free disk $(freegb) GB"
  if [ "${ALL[0]}" = E40 ]; then phases="v7 v4"; else phases="v4 v7"; fi
  for ph in $phases; do PHASE=$ph
    if [ -z "$(pick $ALLS)" ]; then continue; fi
    if [ $ph = v7 ]; then log "--- $sc: UUIDv7 snapshot (E40)"; v7on; else log "--- $sc: UUIDv4 snapshot"; v7off; fi
    run bin/bench gen $D --scenario $sc; snapcheck
    body $sc
    v7off
  done; }

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== round 5 start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}, passes: ${PASSES:-1 2}, variants: ${FWD[*]}, suffix $BASESFX, free disk: $(freegb) GB"
first=1
for PASS in ${PASSES:-1 2}; do
  if [ -z "$first" ]; then log "--- restart postgres between passes"; bin/db.sh stop postgres >>"$LOG" 2>&1; sleep 5; bin/db.sh start postgres >>"$LOG" 2>&1; fi
  first=
  SFX="-v$PASS$BASESFX"; if [ $((PASS % 2)) = 1 ]; then ALL=("${FWD[@]}"); else ALL=("${REV[@]}"); fi
  log "=== pass $PASS, suffix $SFX, order: ${ALL[*]}"
  for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
done
log "DONE round 5"
