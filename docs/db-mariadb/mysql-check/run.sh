#!/usr/bin/env bash
# MySQL check driver (lane 1, MySQL 9.2, dialect mysql). usage: mysql-check/run.sh all   (resumable via harness/bin/run-cells.sh)
# Equivalence B vs A on S1 and S5, then per label A, B, A2 (drift check) back to back on the same snapshot. UUIDv7 ids throughout.
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; MC=$D/mysql-check; RR=$MC/results
W=/home/azamojski/outbox-wt-mdb-opt2
export HARNESS_ID_VERSION=7 DIALECT=mysql HARNESS_SAMPLE_ORDERED=1
MARK=$H/build/snap-mysql-L1.txt
V="my-A=$W,catalog=mdb-opt2 my-A2=$W,catalog=mdb-opt2 my-B=$W,catalog=mdb-opt2,queries=$MC/forms-b"
mkdir -p $RR/logs; LOG=$RR/logs/driver.log; FAIL=$RR/failures.txt
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
chk_pre(){ if [ -f "$MARK" ] && ! grep -q 'ids=uuidv7' "$MARK"; then log "snapshot marker without ids=uuidv7: removing"; rm -f "$MARK"; fi; }
chk_post(){ grep -q 'ids=uuidv7' "$MARK" 2>/dev/null || { log "ABORT: snapshot without ids=uuidv7"; echo "no v7 marker after $1" >> $FAIL; exit 4; }; }
rcell(){ local ba=$1; shift; chk_pre
  log "CELL $* load $(cut -d' ' -f1-3 /proc/loadavg)"
  LANE=1 RUN=mysql-check RESULTS=$RR VARIANTS="$V" MINFREE_GB=10 BENCH_ARGS="$ba" $H/bin/run-cells.sh "$@" >/dev/null 2>&1; local x=$?
  [ $x -ne 0 ] && log "run-cells exit $x for $*"; [ $x -eq 3 ] && exit 3; chk_post "$*"; }
for v in my-A my-A2 my-B; do mkdir -p $RR/$v; printf 'ids=v7\nHARNESS_ID_VERSION=7\n' > $RR/$v/ids.txt; done
pages_keys_ok(){ # all keys of the file exist in the v7 snapshot?
  local f=$D/experiments/r1/s4-pages-keys.txt n m
  n=$(grep -c . $f); m=$(docker exec outbox-bench-mysql-L1 mysql -uroot -pbench -N -e "SELECT COUNT(DISTINCT record_key) FROM snap.rec WHERE record_key IN ($(grep . $f | sed "s/.*/'&'/" | paste -sd,))" 2>/dev/null)
  log "s4 pages keys in snapshot: $m of $n"; [ "$m" = "$n" ]; }
case "${1:-}" in
  all)
    log "##### equiv start"
    rcell "" equiv:my-A+my-B:S1 equiv:my-A+my-B:S5
    grep -rlE "FAIL|ERROR" $RR/equiv 2>/dev/null | while read f; do echo "EQUIV FAIL in $f" >> $FAIL; done
    log "##### timings start"
    for lab in S8-S2 S8-S4 S16-S4 S8-S5 S4 S5 S7; do
      ba="--groups HOT,MON --query-limit-s 60"
      if [ $lab = S4 ]; then
        sc=S4; chk_pre; [ "$(sed -n 's/.*scenario=\([^ ]*\).*/\1/p' $MARK 2>/dev/null)" = S4 ] || { ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/bench gen --dialect mysql --scenario S4 ) >>$LOG 2>&1; }
        pages_keys_ok && ba="$ba --pages-keys-file $D/experiments/r1/s4-pages-keys.txt"
      fi
      for v in my-A my-B my-A2; do rcell "$ba" bench:$v:$lab; done
    done
    log "##### ALL DONE" ;;
  *) echo "usage: run.sh all"; exit 1;;
esac
