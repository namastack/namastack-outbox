#!/usr/bin/env bash
# MySQL confirm driver (lane 1, MySQL 9.2, dialect mysql): keys_* head form (my-head) vs NOT EXISTS (my-ne).
# usage: mysql-check/confirm-run.sh all   (resumable: .state markers + harness finished-result skipping)
# equiv -> pass1 (head, ne; labels forward) -> container stop/start -> pass2 (ne, head; labels reversed) -> bp2g (2G buffer pool). UUIDv7 throughout.
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; MC=$D/mysql-check; RR=$MC/results-confirm; ST=$RR/.state
W=/home/azamojski/outbox-wt-mdb-opt2; C=mysql-L1; CN=outbox-bench-mysql-L1
export HARNESS_ID_VERSION=7 DIALECT=mysql HARNESS_SAMPLE_ORDERED=1
MARK=$H/build/snap-mysql-L1.txt
V="my-head=$W,catalog=mdb-opt2 my-ne=$W,catalog=mdb-opt2,queries=$MC/forms-ne"
mkdir -p $RR/logs $ST; LOG=$RR/logs/driver.log; FAIL=$RR/failures.txt
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
purge(){ docker exec $CN mysql -uroot -pbench -e "PURGE BINARY LOGS BEFORE NOW()" >/dev/null 2>&1; }
chk_pre(){ if [ -f "$MARK" ] && ! grep -q 'ids=uuidv7' "$MARK"; then log "snapshot marker without ids=uuidv7: removing"; rm -f "$MARK"; fi; }
chk_post(){ grep -q 'ids=uuidv7' "$MARK" 2>/dev/null || { log "ABORT: snapshot without ids=uuidv7 after $1"; echo "no v7 marker after $1" >> $FAIL; exit 4; }; }
rcell(){ local ba=$1; shift; chk_pre
  purge
  if [ "$(free_gb)" -lt 20 ]; then log "free $(free_gb)G < 20: dropruns"; ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/bench dropruns --dialect mysql ) >>"$LOG" 2>&1; purge; fi
  log "CELL $* load $(cut -d' ' -f1-3 /proc/loadavg) free $(free_gb)G"
  LANE=1 RUN=mysql-confirm RESULTS=${RES:-$RR} VARIANTS="$V" MINFREE_GB=10 BENCH_ARGS="$ba" CONC_ARGS="${CA:-}" $H/bin/run-cells.sh "$@" >/dev/null 2>&1; local x=$?
  purge
  [ $x -ne 0 ] && log "run-cells exit $x for $*"
  [ $x -eq 3 ] && { log "ABORT disk < 10 GB at $*"; echo "ABORT disk at $*" >> $FAIL; exit 3; }
  [ "$(free_gb)" -lt 10 ] && { log "ABORT free disk $(free_gb)G < 10 after $*"; echo "ABORT disk after $*" >> $FAIL; exit 3; }
  chk_post "$*"; }
ids_files(){ for v in my-head my-ne; do mkdir -p $1/$v; printf 'ids=v7\nHARNESS_ID_VERSION=7\n' > $1/$v/ids.txt; done; }
# limit source: the other run's timings of the same label (pass 1 head: screen A results = same forms; ne/others: head of this or pass 1)
lim(){ local R=$1 v=$2 lab=$3 other f
  [ $v = my-head ] && other=my-ne || other=my-head
  for f in $R/$other/mysql/$lab/timings.csv $RR/pass1/$other/mysql/$lab/timings.csv $MC/results/my-B/mysql/$lab/timings.csv; do [ -f $f ] && { echo "--limit-from $f --limit-factor 2 --limit-min-s 60"; return; }; done; echo ""; }
bench1(){ local R=$1 v=$2 lab=$3
  CA="" rcell "--groups HOT,WRITE,MON --query-limit-s 60 $(lim $R $v $lab)" "bench:$v:$lab"; }
conc1(){ local R=$1 v=$2 lab=$3 ca
  case "$lab" in S10-S2-p1-unthr) ca="--pollers 1 --seconds 60 --rate 0";; S10-S2-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; S10-S4-p4) ca="--pollers 4 --seconds 60 --rate 500";; S10-S4-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; esac
  CA="$ca" rcell "" "concurrency:$v:$lab"; }
cell(){ local R=$1 vs=$2 lab=$3; for v in $vs; do case "$lab" in S10-*) conc1 $R $v $lab;; *) bench1 $R $v $lab;; esac; done; }
LABS="S1 S2 S8-S2 S16-S2 S10-S2-p1-unthr S10-S2-p4-unthr S4 S8-S4 S16-S4 S4-b100 S10-S4-p4 S10-S4-p4-unthr S5 S8-S5 S6 S8-S6 S7 S8-S7"
dopass(){ local pass=$1 R=$RR/$1 vs labs=""; mkdir -p $R; RES=$R
  [ -f $ST/$pass.done ] && { log "$pass already done"; return; }
  if [ $pass = pass1 ]; then vs="my-head my-ne"; labs="$LABS"; else vs="my-ne my-head"; for l in $LABS; do labs="$l $labs"; done; fi
  ids_files $R
  [ -f $ST/$pass.started ] || { rm -f $MARK; touch $ST/$pass.started; }   # gen once per pass: fresh snapshot at the start of a pass
  log "##### $pass start (load $(cut -d' ' -f1-3 /proc/loadavg))"
  for l in $labs; do cell $R "$vs" $l; done
  touch $ST/$pass.done; log "##### $pass DONE"; }
doequiv(){ [ -f $ST/equiv.done ] && return
  echo "ids=v7 HARNESS_ID_VERSION=7" > $RR/equiv-ids.txt
  log "##### equiv start"
  rcell "" equiv:my-head+my-ne:S1 equiv:my-head+my-ne:S2 equiv:my-head+my-ne:S5 equiv:my-head+my-ne:S7
  touch $ST/equiv.done; log "##### equiv DONE"; }
dobp(){ [ -f $ST/bp2g.done ] && return
  local R=$RR/bp2g; mkdir -p $R; ids_files $R; RES=$R
  if [ ! -f $ST/bp2g.started ]; then
    ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/db.sh stop mysql; $H/bin/db.sh rm mysql; HARNESS_EXTRA_ARGS="--innodb-buffer-pool-size=2G" $H/bin/db.sh start mysql ) >>"$LOG" 2>&1
    rm -f $MARK; touch $ST/bp2g.started; fi
  local bp; bp=$(docker exec $CN mysql -uroot -pbench -N -e "SELECT @@innodb_buffer_pool_size" 2>/dev/null)
  echo "innodb_buffer_pool_size=$bp ($(date))" >> $R/buffer-pool.txt; log "bp2g: innodb_buffer_pool_size=$bp"
  [ "$bp" -ge 2147483648 ] && [ "$bp" -le 2684354560 ] || { echo "bp2g: buffer pool is $bp, not 2G" >> $FAIL; exit 5; }
  log "##### bp2g start"
  for l in S8-S2 S8-S5 S5 S10-S2-p4-unthr; do cell $R "my-head my-ne" $l; done
  ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/db.sh stop mysql; $H/bin/db.sh rm mysql; $H/bin/db.sh start mysql ) >>"$LOG" 2>&1
  bp=$(docker exec $CN mysql -uroot -pbench -N -e "SELECT @@innodb_buffer_pool_size" 2>/dev/null); echo "restored default: $bp" >> $R/buffer-pool.txt
  docker stop $CN >/dev/null 2>&1
  touch $ST/bp2g.done; log "##### bp2g DONE (container recreated with default buffer pool, then stopped)"; }
case "${1:-}" in
  all)
    doequiv
    dopass pass1
    if [ ! -f $ST/restart.done ]; then log "container stop/start"; ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/db.sh stop mysql; $H/bin/db.sh start mysql ) >>"$LOG" 2>&1; touch $ST/restart.done; fi
    dopass pass2
    dobp
    log "##### ALL DONE" ;;
  *) echo "usage: confirm-run.sh all"; exit 1;;
esac
