#!/usr/bin/env bash
# Phase-6 final measurement driver. usage: final-run.sh all | restart(=all)
# Stages (markers in results/final/.state/, resumable): equiv (lane 2, before pass 1) -> pass1 -> restart container -> pass2 -> bp2g (2G buffer pool, one pass).
# Lane 1 only for timed cells; lane 2 idle during them. UUIDv4 only. Finished result files are skipped by harness/bin/run-cells.sh.
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; X=$D/experiments; XR=$X/r1
WM=/home/azamojski/outbox-wt-mdb-main; WO=/home/azamojski/outbox-wt-mdb-opt; WF=/home/azamojski/outbox-wt-mdb-final
RR=$D/results/final; MARK=$H/build/snap-mariadb-L1.txt; ST=$RR/.state
unset HARNESS_ID_VERSION
V="mdb-main=$WM,catalog=mdb-main mdb-opt=$WO,catalog=mdb-opt mdb-final=$WF,catalog=mdb-final"
mkdir -p $RR/logs $ST; LOG=$RR/logs/driver.log; FAIL=$RR/failures.txt
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
chk_ids(){ if grep -q 'ids=uuidv7' "$MARK" 2>/dev/null; then log "snapshot marker has ids=uuidv7: removing it"; rm -f "$MARK"; fi; }
rc(){ local R=$1 ord=$2 cell=$3 ba=${4:-}
  [ "$(free_gb)" -lt 20 ] && { log "free $(free_gb)G < 20: dropruns"; ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/bench dropruns --dialect mariadb ) >>"$LOG" 2>&1; }
  chk_ids
  log "CELL $cell [$(basename $R)] load $(cut -d' ' -f1-3 /proc/loadavg) free $(free_gb)G"
  LANE=1 RUN=$(basename $R) RESULTS=$R ORDER=$ord VARIANTS="$V" MINFREE_GB=12 BENCH_ARGS="$ba" $H/bin/run-cells.sh "$cell" >/dev/null 2>&1; local x=$?
  [ $x -ne 0 ] && log "run-cells exit $x for $cell"
  [ $x -eq 3 ] && { echo "ABORT disk at $cell" >> $FAIL; exit 3; }
  grep -q 'ids=uuidv7' "$MARK" 2>/dev/null && { log "ABORT: v7 snapshot after $cell"; echo "v7 snapshot after $cell" >> $FAIL; exit 4; }
  return 0; }
ids_files(){ local R=$1; for v in mdb-main mdb-opt mdb-final; do mkdir -p $R/$v; printf 'ids=v4\nHARNESS_ID_VERSION=unset\n' > $R/$v/ids.txt; done; }
OPT10="S4 S4-b100 S5 S6 S7"
LIMSRC=$RR/pass1/mdb-opt/mariadb
bench1(){ local R=$1 v=$2 lab=$3 ba pk=""
  case "$lab" in S4) pk="--pages-keys-file $XR/s4-pages-keys.txt";; S4-b100) pk="--pages-keys-file $XR/s4-pages-keys-b100.txt";; esac
  case $v in
    mdb-main) ba="--groups HOT,WRITE,MON --query-limit-s 120 $pk";;
    mdb-opt) if [[ " $OPT10 " == *" $lab "* ]]; then ba="--groups HOT,WRITE,MON --query-limit-s 10 $pk"; else ba="--groups HOT,WRITE,MON --query-limit-s 60 $pk"; fi;;
    mdb-final) ba="--groups HOT,WRITE,MON --query-limit-s 60 --limit-from $LIMSRC/$lab/timings.csv --limit-factor 2 --limit-min-s 60 $pk";;
  esac
  rc $R forward "bench:$v:$lab" "$ba"; }
cell_all(){ local R=$1 ord=$2 kind=$3 lab=$4 vs; if [ $ord = forward ]; then vs="mdb-main mdb-opt mdb-final"; else vs="mdb-final mdb-opt mdb-main"; fi
  for v in $vs; do
    case "$kind" in
      bench) bench1 $R $v $lab ;;
      writecost) rc $R forward "writecost:$v:$lab" "" ;;
      conc) local ca; case "$lab" in S10-S2-p1) ca="--pollers 1 --seconds 60 --rate 500";; S10-S2-p1-unthr) ca="--pollers 1 --seconds 60 --rate 0";; S10-S2-p4) ca="--pollers 4 --seconds 60 --rate 500";; S10-S2-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; S10-S4-p4) ca="--pollers 4 --seconds 60 --rate 500";; S10-S4-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; esac
        CONC_ARGS="$ca" rc $R forward "concurrency:$v:$lab" "" ;;
      churn) local ba
        case $v in mdb-main) ba="--groups HOT,WRITE,MON --query-limit-s 120";; mdb-opt) ba="--groups HOT,WRITE,MON --query-limit-s 60";; *) ba="--groups HOT,WRITE,MON --query-limit-s 60 --limit-from $LIMSRC/S9/timings.csv --limit-factor 2 --limit-min-s 60";; esac
        CHURN_ARGS="--retention-max-s 2700" rc $R forward "churn:$v:S9" "$ba" ;;
    esac
  done; }
LABS_P1="bench:S1 bench:S2 bench:S8-S2 bench:S16-S2 writecost:S2-wc churn:S9 conc:S10-S2-p1 conc:S10-S2-p1-unthr conc:S10-S2-p4 conc:S10-S2-p4-unthr bench:S4 bench:S8-S4 bench:S16-S4 bench:S4-b100 writecost:S4-wc conc:S10-S4-p4 conc:S10-S4-p4-unthr bench:S5 bench:S8-S5 bench:S6 bench:S8-S6 bench:S7 bench:S8-S7"
LABS_BP="bench:S8-S2 bench:S8-S4 bench:S8-S5 writecost:S2-wc conc:S10-S2-p4"
dopass(){ local pass=$1 R=$RR/$1 ord labs; mkdir -p $R
  [ -f $ST/$pass.done ] && { log "$pass already done"; return; }
  if [ $pass = pass1 ]; then ord=forward; labs="$LABS_P1"; else ord=reverse; labs=""; for l in $LABS_P1; do case "$l" in churn:S9) ;; *) labs="$l $labs";; esac; done; fi
  ids_files $R
  log "##### $pass start (load $(cut -d' ' -f1-3 /proc/loadavg))"
  for l in $labs; do cell_all $R $ord ${l%%:*} ${l#*:}; done
  touch $ST/$pass.done; log "##### $pass DONE"; }
doequiv(){ [ -f $ST/equiv.done ] && return
  rm -f $H/build/snap-mariadb-L2.txt; echo "ids=v4 HARNESS_ID_VERSION=unset" > $RR/equiv-ids.txt
  local CELLS=""; for s in S1 S2 S5 S7; do CELLS="$CELLS equiv:mdb-final+mdb-main:$s equiv:mdb-final+mdb-opt:$s"; done
  log "##### equiv start (lane 2)"
  LANE=2 RUN=final-equiv RESULTS=$RR MINFREE_GB=15 VARIANTS="$V" $H/bin/run-cells.sh $CELLS >/dev/null 2>&1
  grep -q uuidv7 $H/build/snap-mariadb-L2.txt && echo "ABORT: v7 snapshot in L2" >> $FAIL
  { grep -rlE "FAIL|WARN" $RR/equiv 2>/dev/null | while read f; do echo "== $f"; grep -E "FAIL|WARN|ERROR" $f | head -40; done; } > $RR/failures-equiv.tmp
  [ -s $RR/failures-equiv.tmp ] && { echo "EQUIV FAIL/WARN:" >> $FAIL; cat $RR/failures-equiv.tmp >> $FAIL; }
  rm -f $RR/failures-equiv.tmp
  touch $ST/equiv.done; log "##### equiv DONE"; }
dobp(){ [ -f $ST/bp2g.done ] && return
  local R=$RR/bp2g; mkdir -p $R; ids_files $R
  if [ ! -f $ST/bp2g.started ]; then
    ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/db.sh stop mariadb; $H/bin/db.sh rm mariadb; HARNESS_EXTRA_ARGS="--innodb-buffer-pool-size=2G" $H/bin/db.sh start mariadb ) >>"$LOG" 2>&1
    touch $ST/bp2g.started; fi
  # verify setting (container may be restarted by resume: it keeps its args)
  local bp; bp=$(docker exec outbox-bench-mariadb-L1 mariadb -uroot -pbench -N -e "SELECT @@innodb_buffer_pool_size" 2>/dev/null)
  echo "innodb_buffer_pool_size=$bp ($(date))" >> $R/buffer-pool.txt; log "bp2g: innodb_buffer_pool_size=$bp"
  [ "$bp" = 2147483648 ] || { echo "bp2g: buffer pool is $bp, not 2G" >> $FAIL; exit 5; }
  log "##### bp2g start"
  for l in $LABS_BP; do
    local kind=${l%%:*} lab=${l#*:}
    # back to back main then final on the same snapshot
    for v in mdb-main mdb-final; do
      case $kind in
        bench) LIMSRC=$RR/pass1/mdb-opt/mariadb bench1 $R $v $lab ;;
        writecost) rc $R forward "writecost:$v:$lab" "" ;;
        conc) CONC_ARGS="--pollers 4 --seconds 60 --rate 500" rc $R forward "concurrency:$v:$lab" "" ;;
      esac
    done
  done
  ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/db.sh stop mariadb; $H/bin/db.sh rm mariadb; $H/bin/db.sh start mariadb ) >>"$LOG" 2>&1
  touch $ST/bp2g.done; log "##### bp2g DONE (container recreated with default buffer pool)"; }
case "${1:-}" in
  all|restart)
    doequiv
    dopass pass1
    if [ ! -f $ST/restart.done ]; then log "container stop/start"; ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/db.sh stop mariadb; $H/bin/db.sh start mariadb ) >>"$LOG" 2>&1; touch $ST/restart.done; fi
    dopass pass2
    dobp
    log "##### ALL DONE" ;;
  *) echo "usage: final-run.sh all"; exit 1;;
esac
