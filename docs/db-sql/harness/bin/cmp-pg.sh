#!/usr/bin/env bash
# Driver: current main vs feature/db-jdbc-optimization on PostgreSQL, each with its own shipped DDL and SQL (no overrides). Protocol of round5-pg.sh: two passes, reversed order, restart between.
# Variants: cmp-main (outbox-wt-cmp-main, f4446c8) and cmp-jdbc-opt (outbox-wt-cmp-jdbc-opt, 01165d15). Catalogs: bin/extract-catalog.sh <variant> <worktree>.
# Results: results/<variant>/postgres/<label>-v<pass>-CMP/, equivalence results/equiv/postgres/<scenario>/cmp-jdbc-opt-vs-cmp-main.md. Logs: results/logs/cmp-pg.log, cmp-pg.out, failures-cmp.txt. Resumable (REDO=1 repeats).
# Env: SCENARIOS (S2 S4 S5 S6 S7), PASSES (1 2), R5_SUFFIX, R5_LOGX, BENCHARGS, WCARGS, CHURNN (500000), S10SECONDS (60), S10RUNS (2), MINGB (5).
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LX=${R5_LOGX:-}
LOG="$L/cmp-pg$LX.log"; OUT="$L/cmp-pg$LX.out"; FAIL="$L/failures-cmp$LX.txt"
WT_cmain=/home/zamo/git/outbox-wt-cmp-main; WT_copt=/home/zamo/git/outbox-wt-cmp-jdbc-opt
D="--dialect postgres"
BASESFX=${R5_SUFFIX:--CMP}
SFX=$BASESFX
MINGB=${MINGB:-5}
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
DEF[cmp-main]="cmp-main|cmain||"; DEF[cmp-jdbc-opt]="cmp-jdbc-opt|copt||"
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]:-v1|v1||}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; [ $n -eq 0 ] && return 0; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }
# restricted runs: extra bench args per variant
onlyfor(){ case $1 in P-IN|P-UNO) echo "--only pages,keys_strict";; OD-OB) echo "--only oldest_due_nonstrict,keys_strict";; esac; }

FWD=(cmp-main cmp-jdbc-opt); [ -n "${VARIANTS:-}" ] && FWD=($VARIANTS)
REV=(); for ((i=${#FWD[@]}-1;i>=0;i--)); do REV+=(${FWD[$i]}); done
ALL=("${FWD[@]}"); PHASE=v4
# pick <variants...>: those of the list that are selected, in pass order, belonging to the current snapshot phase (v7: E40 only; v4: all but E40)
pick(){ local v w; for v in "${ALL[@]}"; do for w in "$@"; do
    [ "$v" = "$w" ] || continue
    if [ "$PHASE" = v7 ]; then [ "$v" = E40 ] && echo $v; else [ "$v" != E40 ] && echo $v; fi; done; done; }
FULLV="cmp-main cmp-jdbc-opt"; RESTR_P=""; RESTR_O=""

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
EQ(){ local v=$1 sc=$2; local rep="$R/equiv/postgres/$sc/cmp-jdbc-opt-vs-cmp-main.md"
  step "$rep" bin/bench equiv $D $(eqa cmp-jdbc-opt a) $(eqa cmp-main b) --scenario $sc
  if [ -e "$rep" ]; then local nf; nf=$(grep -cE '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep")
    if [ "$nf" -gt 0 ]; then log "EQUIV FAIL: cmp-jdbc-opt vs cmp-main on $sc ($nf lines with FAIL/ERROR in $rep): $(grep -E '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep" | cut -c1-300 | tr '\n' ' ')"
    else log "equiv ok: cmp-jdbc-opt vs cmp-main on $sc (no FAIL)"; fi; fi; }
equiv_all(){ local sc=$1 v; [ "$PHASE" = v4 ] || return 0; EQ cmp-jdbc-opt $sc; }
snapcheck(){ local f=build/snap-postgres.txt
  log "snapcheck: $(tr '\n' ' ' < $f 2>/dev/null) ids(version nibble of 2000 snapshot ids; 4=v4, 7=v7)=$(docker exec outbox-bench-postgres psql -U bench -d bench -Atc "SELECT string_agg(DISTINCT substr(id,15,1), ',') FROM (SELECT id FROM snap.rec LIMIT 2000) s" 2>&1 | head -1)"; }

ALLS="cmp-main cmp-jdbc-opt"
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
log "=== cmp start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}, passes: ${PASSES:-1 2}, variants: ${FWD[*]}, suffix $BASESFX, free disk: $(freegb) GB"
first=1
for PASS in ${PASSES:-1 2}; do
  if [ -z "$first" ]; then log "--- restart postgres between passes"; bin/db.sh stop postgres >>"$LOG" 2>&1; sleep 5; bin/db.sh start postgres >>"$LOG" 2>&1; fi
  first=
  SFX="-v$PASS$BASESFX"; if [ $((PASS % 2)) = 1 ]; then ALL=("${FWD[@]}"); else ALL=("${REV[@]}"); fi
  log "=== pass $PASS, suffix $SFX, order: ${ALL[*]}"
  for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do doscen $sc; done
done
log "DONE cmp"
