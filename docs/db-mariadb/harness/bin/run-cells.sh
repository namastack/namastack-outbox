#!/usr/bin/env bash
# Resumable cell driver for ONE lane (MariaDB). Modelled on _template-serial-driver.sh.
#
# usage: LANE=1 RUN=<run-name> VARIANTS="mdb-main=/path/wt mdb-opt=/path/wt,ddl=F,queries=D,catalog=NAME" \
#          [RESULTS=dir] [ORDER=forward|reverse] [REDO=1] [MINFREE_GB=8] bin/run-cells.sh <cell>...
#
# Cell = <kind>:<variant>:<label>   kinds: bench | writecost | concurrency | churn | equiv
#   variant  a name from VARIANTS, or '*' = every variant of VARIANTS in ORDER (forward = as listed, reverse = reversed).
#            equiv takes <a>+<b> (or '*' = first two variants): equiv:mdb-main+mdb-opt:S1
#   label    S1 | S2 | S4 | S5 ... plain scenario; S8-S2 (S2 data, --owned mod4); S4-b100 (--batch 100); S2-wc (S2 data);
#            S9 (churn on S2 data); S10 (concurrency on S2 data). Anything else: scenario = text before the first '-'.
# Examples: bench:*:S1   bench:*:S8-S2   writecost:*:S2-wc   churn:*:S9   concurrency:*:S10   equiv:*:S1
#
# Per cell: `gen` only when the lane's snapshot marker holds another scenario; `prepare` before every writing step; finished cells are skipped
# (a result file exists; REDO=1 repeats). Bench cells run --groups HOT,WRITE,MON unless BENCH_ARGS is set (BENCH_ARGS replaces the whole
# option string, e.g. "--groups HOT,WRITE,MON,RET --query-limit-s 900"). WC_ARGS, CONC_ARGS (default "--pollers 4 --seconds 60 --rate 500",
# tag = TAG or p<pollers>-r<rate>), CHURN_ARGS pass extra options. Logs: <results>/logs/<RUN>-L<lane>.log, failures: <results>/logs/<RUN>-failures.txt.
# Aborts (exit 3) when free disk < MINFREE_GB. Starts the lane's container if needed; leaves it running (bin/cleanup.sh removes it).
set -u
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
LANE="${LANE:?LANE=1|2}"; RUN="${RUN:?RUN=<run-name>}"; VARIANTS="${VARIANTS:?VARIANTS=name=worktree[,ddl=F][,queries=D][,catalog=N] ...}"
R="${RESULTS:-$H/../results}"; mkdir -p "$R/logs"; R="$(cd "$R" && pwd)"; LOG="$R/logs/$RUN-L$LANE.log"; FAIL="$R/logs/$RUN-failures.txt"
MINFREE_GB="${MINFREE_GB:-8}"; STEP_T=$(( ${STEP_TIMEOUT_H:-6} * 3600 )); ORDER="${ORDER:-forward}"; DIALECT="${DIALECT:-mariadb}"
# shellcheck disable=SC1091
source "$H/bin/lane.sh" "$LANE" >/dev/null || exit 1
log(){ echo "[$(date '+%m-%d %H:%M:%S')] L$LANE $*" | tee -a "$LOG"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
declare -A WT DDL QRY CAT; NAMES=()
for spec in $VARIANTS; do
  IFS=, read -r -a parts <<< "$spec"; n="${parts[0]%%=*}"; WT[$n]="${parts[0]#*=}"; NAMES+=("$n")
  for p in "${parts[@]:1}"; do case "$p" in ddl=*) DDL[$n]="${p#ddl=}";; queries=*) QRY[$n]="${p#queries=}";; catalog=*) CAT[$n]="${p#catalog=}";; *) echo "bad variant option $p"; exit 1;; esac; done
done
vargs(){ local n=$1 a="--variant $1 --worktree ${WT[$1]}"; [ -n "${DDL[$n]:-}" ] && a+=" --ddl ${DDL[$n]}"; [ -n "${QRY[$n]:-}" ] && a+=" --queries ${QRY[$n]}"; [ -n "${CAT[$n]:-}" ] && a+=" --catalog-variant ${CAT[$n]}"; echo "$a"; }
ordered(){ if [ "$ORDER" = reverse ]; then for ((i=${#NAMES[@]}-1;i>=0;i--)); do echo "${NAMES[$i]}"; done; else printf '%s\n' "${NAMES[@]}"; fi; }
scen_of(){ case "$1" in S8-*) echo "${1#S8-}";; S16-*) echo "${1#S16-}";; S9|S10) echo S2;; S10-*) local x="${1#S10-}"; echo "${x%%-*}";; *) echo "${1%%-*}";; esac; }
extra_of(){ case "$1" in S8-*) echo "--owned mod4";; S16-*) echo "--owned $(seq -s, 0 16 240)";; *-b100) echo "--batch 100";; *) echo "";; esac; }
step(){ # step <description> <done-file|-> cmd...
  local d=$1 done=$2; shift 2
  if [ "$done" != "-" ] && [ -f "$done" ] && [ "${REDO:-0}" != 1 ]; then log "SKIP (done) $d"; return 0; fi
  if [ "$(free_gb)" -lt "$MINFREE_GB" ]; then log "ABORT: free disk $(free_gb) GB < $MINFREE_GB GB before: $d"; echo "ABORT disk $(free_gb)G before: $d" >> "$FAIL"; exit 3; fi
  log "RUN $d (free $(free_gb)G, loadavg $(cut -d' ' -f1-3 /proc/loadavg))"
  timeout "$STEP_T" "$@" >>"$LOG" 2>&1; local rc=$?
  [ $rc -ne 0 ] && { log "FAILED rc=$rc: $d"; echo "FAILED rc=$rc L$LANE: $d" >> "$FAIL"; }
  return $rc; }
ensure_gen(){ local sc=$1 cur; cur="$(sed -n 's/.*scenario=\([^ ]*\).*/\1/p' "$H/build/snap-$DIALECT-L$LANE.txt" 2>/dev/null)"
  [ "$cur" = "$sc" ] && return 0
  step "gen $sc" - bin/bench gen --dialect $DIALECT --scenario "$sc"; }
prep(){ local v=$1 sc=$2 lab=$3; step "prepare $v $lab" - bin/bench prepare --dialect $DIALECT $(vargs "$v") --scenario "$sc" --label "$lab" --results "$R"; }

docell(){ local kind=$1 vsel=$2 lab=$3 sc ex vs v
  sc="$(scen_of "$lab")"; ex="$(extra_of "$lab")"
  docker ps --format '{{.Names}}' | grep -qx "outbox-bench-$DIALECT-L$LANE" || bin/db.sh start $DIALECT >>"$LOG" 2>&1
  if [ "$kind" = equiv ]; then
    local a b; if [ "$vsel" = '*' ]; then a="${NAMES[0]}"; b="${NAMES[1]}"; else a="${vsel%+*}"; b="${vsel#*+}"; fi
    local f="$R/equiv/$DIALECT/$sc/$a-vs-$b.md"
    [ -f "$f" ] && [ "${REDO:-0}" != 1 ] && { log "SKIP (done) equiv $a $b $sc"; return 0; }
    ensure_gen "$sc" || return 1
    local ea="" eb=""; [ -n "${DDL[$a]:-}" ] && ea+=" --ddl-a ${DDL[$a]}"; [ -n "${QRY[$a]:-}" ] && ea+=" --queries-a ${QRY[$a]}"; [ -n "${CAT[$a]:-}" ] && ea+=" --catalog-variant-a ${CAT[$a]}"
    [ -n "${DDL[$b]:-}" ] && eb+=" --ddl-b ${DDL[$b]}"; [ -n "${QRY[$b]:-}" ] && eb+=" --queries-b ${QRY[$b]}"; [ -n "${CAT[$b]:-}" ] && eb+=" --catalog-variant-b ${CAT[$b]}"
    HARNESS_SAMPLE_ORDERED=1 step "equiv $a vs $b $sc" "$f" bin/bench equiv --dialect $DIALECT --a "$a" --b "$b" --worktree-a "${WT[$a]}" --worktree-b "${WT[$b]}" $ea $eb --scenario "$sc" --results "$R" $ex
    return $?
  fi
  if [ "$vsel" = '*' ]; then vs="$(ordered)"; else vs="$vsel"; fi
  for v in $vs; do
    local od="$R/$v/$DIALECT/$lab"
    ensure_gen "$sc" || return 1
    case "$kind" in
      bench) step "bench $v $lab" "$od/timings.csv" bin/bench bench --dialect $DIALECT $(vargs "$v") --scenario "$sc" --label "$lab" --prepare --results "$R" ${BENCH_ARGS:---groups HOT,WRITE,MON} $ex ;;
      writecost) [ -f "$od/writecost.csv" ] && [ "${REDO:-0}" != 1 ] && { log "SKIP (done) writecost $v $lab"; continue; }
        prep "$v" "$sc" "$lab" && step "writecost $v $lab" "$od/writecost.csv" bin/bench writecost --dialect $DIALECT $(vargs "$v") --scenario "$sc" --label "$lab" --results "$R" ${WC_ARGS:-} $ex ;;
      concurrency) local ca="${CONC_ARGS:---pollers 4 --seconds 60 --rate 500}" pl rt tag
        pl="$(echo "$ca" | sed -n 's/.*--pollers \([0-9]*\).*/\1/p')"; rt="$(echo "$ca" | sed -n 's/.*--rate \([0-9]*\).*/\1/p')"; tag="${TAG:-p${pl:-1}-r${rt:-500}}"
        [ -f "$od/concurrency-$tag.txt" ] && [ "${REDO:-0}" != 1 ] && { log "SKIP (done) concurrency $v $lab $tag"; continue; }
        prep "$v" "$sc" "$lab" && step "concurrency $v $lab $tag" "$od/concurrency-$tag.txt" bin/bench concurrency --dialect $DIALECT $(vargs "$v") --scenario "$sc" --label "$lab" --results "$R" $ca --tag "$tag" $ex ;;
      churn) [ -f "$od/churn.txt" ] && [ "${REDO:-0}" != 1 ] && { log "SKIP (done) churn $v $lab"; continue; }
        # S9: churn on a fresh schema, then the P1/P2 bench on the churned table (no --prepare, no ANALYZE/OPTIMIZE)
        prep "$v" "$sc" "$lab" && step "churn $v $lab" "$od/churn.txt" bin/bench churn --dialect $DIALECT $(vargs "$v") --scenario "$sc" --label "$lab" --results "$R" ${CHURN_ARGS:-} $ex &&
        step "bench-after-churn $v $lab" - bin/bench bench --dialect $DIALECT $(vargs "$v") --scenario "$sc" --label "$lab" --results "$R" ${BENCH_ARGS:---groups HOT,WRITE,MON} $ex ;;
      *) log "unknown cell kind $kind"; echo "unknown cell kind $kind" >> "$FAIL" ;;
    esac
  done; }

[ $# -gt 0 ] || { echo "no cells given (see header)"; exit 1; }
log "##### run $RUN lane $LANE order=$ORDER variants: ${NAMES[*]} cells: $*"
bin/db.sh start $DIALECT >>"$LOG" 2>&1
for cell in "$@"; do
  IFS=: read -r kind vsel lab <<< "$cell"
  [ -n "${lab:-}" ] || { log "bad cell '$cell' (kind:variant:label)"; echo "bad cell $cell" >> "$FAIL"; continue; }
  docell "$kind" "$vsel" "$lab" || log "cell $cell had a failing step (continuing)"
done
log "DONE run $RUN lane $LANE"
