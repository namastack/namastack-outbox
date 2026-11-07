#!/usr/bin/env bash
# Confirm-stage driver (lane 1, serial; lane 2 idle during every timed step). usage: run-c.sh pass1 | pass2 | iso | restart
#   pass1: order c-ref c-1 c-2, scenarios in the phase-3 order, S9 included.  pass2: order c-2 c-1 c-ref, scenarios reversed, no S9.
#   Resumable: finished result files are skipped by harness/bin/run-cells.sh. UUIDv4 only (HARNESS_ID_VERSION is never set; every snapshot marker is checked for ids=uuidv7 before each step).
#   Each (variant, label) is one run-cells.sh invocation; the three variants of a label go back to back on the same snapshot (gen happens when the scenario changes).
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; X=$D/experiments; XR=$X/r1; OPT=/home/azamojski/outbox-wt-mdb-opt
RR=$D/results/c; MARK=$H/build/snap-mariadb-L1.txt
unset HARNESS_ID_VERSION
V="c-ref=$OPT,catalog=mdb-opt c-1=$OPT,ddl=$X/r2/spc/ddl.sql,queries=$X/r2/forms-a/queries,catalog=mdb-opt c-2=$OPT,ddl=$X/c/c-2/ddl.sql,queries=$X/r2/forms-e5h/queries,catalog=mdb-opt"
VISO="c-1=$OPT,ddl=$X/r2/spc/ddl.sql,queries=$X/r2/forms-a/queries,catalog=mdb-opt c-1-no4=$OPT,ddl=$X/r2/base/ddl.sql,queries=$X/r2/forms-a/queries,catalog=mdb-opt c-1b=$OPT,ddl=$X/r2/spc/ddl.sql,queries=$X/r2/forms-a/queries,catalog=mdb-opt"
mkdir -p $RR/logs; LOG=$RR/logs/driver.log; FAIL=$RR/failures.txt
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
chk_ids(){ if grep -q 'ids=uuidv7' "$MARK" 2>/dev/null; then log "snapshot marker has ids=uuidv7: removing it (forces a v4 gen)"; rm -f "$MARK"; fi; }
# cell <results-dir> <variants-spec> <ORDER> <kind:variant:label> [BENCH_ARGS] [extra env assignments via CONC_ARGS/CHURN_ARGS/WC_ARGS exported by caller]
rc(){ local R=$1 vs=$2 ord=$3 cell=$4 ba=${5:-}; local lab=${cell##*:}
  [ "$(free_gb)" -lt 20 ] && { log "free $(free_gb)G < 20: dropruns"; ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/bench dropruns --dialect mariadb ) >>"$LOG" 2>&1; }
  chk_ids
  log "CELL $cell [$R] load $(cut -d' ' -f1-3 /proc/loadavg) free $(free_gb)G"
  LANE=1 RUN=$(basename $R) RESULTS=$R ORDER=$ord VARIANTS="$vs" MINFREE_GB=12 BENCH_ARGS="$ba" $H/bin/run-cells.sh "$cell" >/dev/null 2>&1; local x=$?
  [ $x -ne 0 ] && log "run-cells exit $x for $cell"
  [ $x -eq 3 ] && { echo "ABORT disk at $cell" >> $FAIL; exit 3; }
  grep -q 'ids=uuidv7' "$MARK" 2>/dev/null && { log "ABORT: v7 snapshot after $cell"; echo "v7 snapshot after $cell" >> $FAIL; exit 4; }
  return 0; }
ids_files(){ local R=$1; shift; for v in "$@"; do mkdir -p $R/$v; printf 'ids=v4\nHARNESS_ID_VERSION=unset\n' > $R/$v/ids.txt; done; }
OPTS_REF10="S4 S4-b100 S5 S6 S7"
# bench cell for one variant, limits as in the spec
bench1(){ local R=$1 pass=$2 v=$3 lab=$4 ba pk=""
  case "$lab" in S4) pk="--pages-keys-file $XR/s4-pages-keys.txt";; S4-b100) pk="--pages-keys-file $XR/s4-pages-keys-b100.txt";; esac
  if [ "$v" = c-ref ]; then
    if [[ " $OPTS_REF10 " == *" $lab "* ]]; then ba="--groups HOT,WRITE,MON --query-limit-s 10 $pk"; else ba="--groups HOT,WRITE,MON --query-limit-s 60 $pk"; fi
  else ba="--groups HOT,WRITE,MON --query-limit-s 60 --limit-from $RR/pass1/c-ref/mariadb/$lab/timings.csv --limit-factor 2 --limit-min-s 60 $pk"; fi
  rc $R "$V" forward "bench:$v:$lab" "$ba"; }
cell_all(){ local R=$1 pass=$2 ord=$3 kind=$4 lab=$5 vs; if [ $ord = forward ]; then vs="c-ref c-1 c-2"; else vs="c-2 c-1 c-ref"; fi
  for v in $vs; do
    case "$kind" in
      bench) bench1 $R $pass $v $lab ;;
      writecost) rc $R "$V" forward "writecost:$v:$lab" "" ;;
      conc) local ca; case "$lab" in S10-S2-p1) ca="--pollers 1 --seconds 60 --rate 500";; S10-S2-p1-unthr) ca="--pollers 1 --seconds 60 --rate 0";; S10-S2-p4) ca="--pollers 4 --seconds 60 --rate 500";; S10-S2-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; S10-S4-p4) ca="--pollers 4 --seconds 60 --rate 500";; S10-S4-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; esac
        CONC_ARGS="$ca" rc $R "$V" forward "concurrency:$v:$lab" "" ;;
      churn) local ba="--groups HOT,WRITE,MON --query-limit-s 60"; [ $v != c-ref ] && ba="$ba --limit-from $RR/pass1/c-ref/mariadb/S9/timings.csv --limit-factor 2 --limit-min-s 60"
        CHURN_ARGS="--retention-max-s 2700" rc $R "$V" forward "churn:$v:S9" "$ba" ;;
    esac
  done; }
LABS_P1="bench:S1 bench:S2 bench:S8-S2 bench:S16-S2 writecost:S2-wc churn:S9 conc:S10-S2-p1 conc:S10-S2-p1-unthr conc:S10-S2-p4 conc:S10-S2-p4-unthr bench:S4 bench:S8-S4 bench:S16-S4 bench:S4-b100 writecost:S4-wc conc:S10-S4-p4 conc:S10-S4-p4-unthr bench:S5 bench:S8-S5 bench:S6 bench:S8-S6 bench:S7 bench:S8-S7"
dopass(){ local pass=$1 R=$RR/$1 ord labs; mkdir -p $R
  if [ $pass = pass1 ]; then ord=forward; labs="$LABS_P1"; else ord=reverse; labs=""; for l in $LABS_P1; do case "$l" in churn:S9) ;; *) labs="$l $labs";; esac; done; fi
  ids_files $R c-ref c-1 c-2
  log "##### $pass start (sha $(git -C $OPT rev-parse --short HEAD), load $(cut -d' ' -f1-3 /proc/loadavg))"
  for l in $labs; do cell_all $R $pass $ord ${l%%:*} ${l#*:}; done
  log "##### $pass DONE"; }
doiso(){ local R=$RR/iso; mkdir -p $R; ids_files $R c-1 c-1-no4 c-1b
  local IA="--only keys_strict,keys_nonstrict,oldest_created,oldest_due_strict,oldest_due_nonstrict --groups HOT,MON --reps 50 --warmup 10 --query-limit-s 60 --no-plans"
  log "##### iso start"
  for lab in S8-S4 S16-S4 S4 S8-S5; do for v in c-1 c-1-no4 c-1b; do
    local pk=""; [ $lab = S4 ] && pk="--pages-keys-file $XR/s4-pages-keys.txt"
    rc $R "$VISO" forward "bench:$v:$lab" "$IA $pk"; done; done
  # planning time: prepare each schema on the S4 and S5 snapshot, then EXPLAIN timing + optimizer trace
  for sc in S4 S5; do
    ( source $H/bin/lane.sh 1 >/dev/null; cd $H
      cur=$(sed -n 's/.*scenario=\([^ ]*\).*/\1/p' $MARK 2>/dev/null); [ "$cur" = $sc ] || bin/bench gen --dialect mariadb --scenario $sc ) >>"$LOG" 2>&1
    chk_ids
    for v in c-1 c-1-no4 c-1b; do
      local spec; for s in $VISO; do [ "${s%%=*}" = $v ] && spec=$s; done
      IFS=, read -r -a parts <<< "$spec"; local wt="${parts[0]#*=}" ddl="" qd="" cat=""
      for p in "${parts[@]:1}"; do case $p in ddl=*) ddl=${p#ddl=};; queries=*) qd=${p#queries=};; catalog=*) cat=${p#catalog=};; esac; done
      local va="--variant $v --worktree $wt --ddl $ddl --queries $qd --catalog-variant $cat --scenario $sc --label plantime-$sc --results $R"
      log "plantime $v $sc load $(cut -d' ' -f1-3 /proc/loadavg)"
      ( source $H/bin/lane.sh 1 >/dev/null; cd $H; bin/bench prepare --dialect mariadb $va && bin/bench plantime --dialect mariadb ${va/plantime-$sc/plantime-$sc-all} --reps 20 --owned all && bin/bench plantime --dialect mariadb ${va/plantime-$sc/plantime-$sc-mod4} --reps 20 --owned mod4 ) >>"$LOG" 2>&1 || { log "plantime failed $v $sc"; echo "FAILED plantime $v $sc" >> $FAIL; }
    done
  done
  ( source $H/bin/lane.sh 1 >/dev/null; cd $H; bin/bench dropruns --dialect mariadb ) >>"$LOG" 2>&1
  log "##### iso DONE"; }
case "${1:-}" in pass1) dopass pass1;; pass2) dopass pass2;; iso) doiso;; *) echo usage; exit 1;; esac
