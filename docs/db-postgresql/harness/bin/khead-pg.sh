#!/usr/bin/env bash
# Driver: keys_strict / keys_nonstrict form test on PostgreSQL. K-REF = branch NOT EXISTS form, K-HEAD = scalar
# "o.id = (SELECT e.id ... ORDER BY e.created_at, e.id LIMIT 1)" form (experiments/K-HEAD/queries). Both use the
# DDL of feature/db-jdbc-optimization at 270c42cf (worktree ../outbox-wt-khead) and the P-KK2 pages form (= the branch's
# pages at 270c42cf). Base catalog cmp-jdbc-opt (01165d15): its PostgreSQL keys_*, insert and upd_complete SQL equal 270c42cf. Copy of omin-pg.sh, suffix -KHEAD.
# Results: results/<variant>/postgres/<label>-v<pass>-KHEAD/, equivalence results/equiv/postgres/<scenario>/K-HEAD-vs-K-REF.md.
# Logs: results/logs/khead-pg.log, khead-pg.out, failures-khead.txt. Resumable (REDO=1 repeats).
# Env: SCENARIOS (S2 S4 S5 S6 S7), PASSES (1 2), S10SCEN (S2 S4), S10SECONDS (60), S10RUNS (2), MINGB (5), BENCHARGS,
#      FORCE=1 starts even if another outbox-bench container is running (timings would not be valid).
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="$H/../results"; L="$R/logs"; X="$H/../experiments"; mkdir -p "$L"
LOG="$L/khead-pg.log"; OUT="$L/khead-pg.out"; FAIL="$L/failures-khead.txt"
WT_copt=/home/zamo/git/outbox-wt-khead; PIN=270c42cf
D="--dialect postgres"
BASESFX=-KHEAD; SFX=$BASESFX
MINGB=${MINGB:-5}
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
freegb(){ df -BG --output=avail "$R" | tail -1 | tr -dc 0-9; }
run(){ local g; g=$(freegb); if [ "$g" -lt "$MINGB" ]; then log "ABORT: free disk ${g} GB < ${MINGB} GB"; echo "ABORT: free disk ${g} GB" >> "$FAIL"; exit 3; fi
  log "RUN: $*"; "$@" >>"$OUT" 2>&1; rc=$?
  if [ $rc -ne 0 ]; then log "FAILED rc=$rc: $*"; echo "FAILED rc=$rc: $*" >> "$FAIL"; fi; return $rc; }
step(){ local m=$1; shift; if [ -z "${REDO:-}" ] && [ -e "$m" ]; then log "skip (done): ${*:1:5}... -> $m"; return 0; fi; run "$@"; }
res(){ echo "$R/$1/postgres/$2"; }

# preflight: no other benchmark on this machine, pinned worktree present
others=$(docker ps --format '{{.Names}}' | grep '^outbox-bench-' | grep -v '^outbox-bench-postgres$' | tr '\n' ' ')
if [ -n "$others" ] && [ -z "${FORCE:-}" ]; then echo "ABORT: other benchmark containers running: $others(stop them or set FORCE=1)"; exit 4; fi
if [ ! -d "$WT_copt" ]; then
  echo "creating worktree $WT_copt at $PIN"; git -C "$H" worktree add --detach "$WT_copt" "$PIN" || exit 5; fi
[ "$(git -C "$WT_copt" rev-parse --short=8 HEAD)" = "$PIN" ] || { echo "ABORT: $WT_copt is not at $PIN"; exit 5; }

declare -A DEF=()
DEF[K-REF]="cmp-jdbc-opt|copt||K-REF"; DEF[K-HEAD]="cmp-jdbc-opt|copt||K-HEAD"
va(){ local v=$1; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--variant $v --worktree ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant $cv"
  [ -n "$dd" ] && a="$a --ddl $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries $X/$qd/queries"; echo $a; }
eqa(){ local v=$1 t=$2; IFS='|' read cv wt dd qd <<<"${DEF[$v]}"; local w=WT_$wt
  local a="--$t $v --worktree-$t ${!w}"; [ "$cv" != "$v" ] && a="$a --catalog-variant-$t $cv"
  [ -n "$dd" ] && a="$a --ddl-$t $X/$dd/ddl.sql"; [ -n "$qd" ] && a="$a --queries-$t $X/$qd/queries"; echo $a; }
rot(){ local off=$1; shift; local a=("$@") n=$# i; [ $n -eq 0 ] && return 0; for ((i=0;i<n;i++)); do echo ${a[$(( (i+off) % n ))]}; done; }
ONLY="--only keys_strict,keys_nonstrict"

FWD=(K-REF K-HEAD); REV=(K-HEAD K-REF); ALL=("${FWD[@]}")

# B <variant> <label> <scenario> [extra args]: fresh prepare + bench (5 warmup, 20 reps; pgjdbc goes generic after 5)
B(){ local v=$1 lab=$2 sc=$3; shift 3
  step "$(res $v $lab$SFX)/timings.csv" bin/bench bench $D $(va $v) --scenario $sc --label "$lab$SFX" --prepare --warmup 5 --reps 20 ${BENCHARGS:-} "$@" $ONLY; }
matrix(){ local lab=$1 sc=$2; shift 2; log "--- label $lab$SFX (scenario $sc $*) order: ${ALL[*]}"; for v in "${ALL[@]}"; do B $v $lab $sc "$@"; done; }
# S10: keys_strict + P-KK2 pages + upd_complete loop, 4 pollers, inserts unthrottled; auto_explain logs statements >= 5 ms
S10(){ local v=$1 sc=$2 run=$3 p=4 lab="S10-$2-r$3$SFX" dir; dir=$(res $v $lab)
  step "$dir/concurrency-p$p-r0.txt" bash -c "bin/bench prepare $D $(va $v) --scenario $sc --label $lab && bin/bench concurrency $D $(va $v) --capture-plans --scenario $sc --label $lab --pollers $p --rate 0 --seconds ${S10SECONDS:-60}"
  log "S10 $v $lab: $(grep -h -E '^(processed_per_s|keys_query_ms)' "$dir"/concurrency-p$p-r0.txt 2>/dev/null | tr '\n' ' ')"; }
s10_all(){ local sc=$1 r ord v
  for r in $(seq 1 ${S10RUNS:-2}); do ord=$(rot $((r - 1)) "${ALL[@]}" | tr '\n' ' ')
    log "--- S10 on $sc run $r$SFX order: $ord"; for v in $ord; do S10 $v $sc $r; done; done; }
EQ(){ local sc=$1; local rep="$R/equiv/postgres/$sc/K-HEAD-vs-K-REF.md"
  step "$rep" bin/bench equiv $D $(eqa K-HEAD a) $(eqa K-REF b) --scenario $sc
  if [ -e "$rep" ]; then local nf; nf=$(grep -cE '^\| [^|]+ \| (FAIL|ERROR) \|' "$rep")
    if [ "$nf" -gt 0 ]; then log "EQUIV FAIL: K-HEAD vs K-REF on $sc ($nf FAIL/ERROR lines in $rep)"; else log "equiv ok: K-HEAD vs K-REF on $sc"; fi; fi; }
equiv_once(){ [ "$PASS" = 1 ] && EQ $1; }
s10_once(){ [ "$PASS" = 1 ] && [[ " ${S10SCEN:-S2 S4} " == *" $1 "* ]] && s10_all $1; }

body(){ local sc=$1
  case $sc in
  S1) matrix S8-S1 S1 --owned mod4 --groups HOT; matrix S1 S1 --groups HOT; equiv_once S1 ;;
  S2) matrix S8-S2 S2 --owned mod4 --groups HOT; matrix S2 S2 --groups HOT; equiv_once S2; s10_once S2 ;;
  S4) matrix S8-S4 S4 --owned mod4 --groups HOT; matrix S4 S4 --groups HOT; s10_once S4 ;;
  S5) matrix S8-S5 S5 --owned mod4 --groups HOT; matrix S5 S5 --groups HOT; equiv_once S5 ;;
  S6) matrix S8-S6 S6 --owned mod4 --groups HOT; matrix S6 S6 --groups HOT; equiv_once S6 ;;
  S7) matrix S8-S7 S7 --owned mod4 --groups HOT; matrix S7 S7 --groups HOT; equiv_once S7 ;;
  esac; }

bin/db.sh start postgres >>"$LOG" 2>&1
log "=== khead start, scenarios: ${SCENARIOS:-S2 S4 S5 S6 S7}, passes: ${PASSES:-1 2}, S10 on: ${S10SCEN:-S2 S4} (pass 1), free disk: $(freegb) GB"
first=1
for PASS in ${PASSES:-1 2}; do
  if [ -z "$first" ]; then log "--- restart postgres between passes"; bin/db.sh stop postgres >>"$LOG" 2>&1; sleep 5; bin/db.sh start postgres >>"$LOG" 2>&1; fi
  first=
  SFX="-v$PASS$BASESFX"; if [ $((PASS % 2)) = 1 ]; then ALL=("${FWD[@]}"); else ALL=("${REV[@]}"); fi
  log "=== pass $PASS, suffix $SFX, order: ${ALL[*]}"
  for sc in ${SCENARIOS:-S2 S4 S5 S6 S7}; do log "=== pass $PASS scenario $sc, free disk $(freegb) GB"; run bin/bench gen $D --scenario $sc; body $sc; done
done
log "DONE khead"
