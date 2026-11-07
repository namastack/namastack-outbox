#!/usr/bin/env bash
# mdb-opt2 vs mdb-final (spec experiments/opt2-spec.md). Resumable (run-cells skips finished results). usage: opt2-run.sh all
# Equivalence on lane 2 first; then solo lane-1 cells, mdb-opt2 then mdb-final back to back; UUIDv4 only.
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; XR=$D/experiments/r1
WO=/home/azamojski/outbox-wt-mdb-opt2; WF=/home/azamojski/outbox-wt-mdb-final
RR=$D/results/opt2; MARK=$H/build/snap-mariadb-L1.txt; ST=$RR/.state; mkdir -p $RR/logs $ST
unset HARNESS_ID_VERSION
V="mdb-opt2=$WO,catalog=mdb-opt2 mdb-final=$WF,catalog=mdb-final"
LOG=$RR/logs/driver.log; FAIL=$RR/failures.txt
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
rc(){ local lane=$1 cell=$2 ba=${3:-}
  [ "$(free_gb)" -lt 20 ] && { log "free $(free_gb)G < 20: dropruns"; ( source $H/bin/lane.sh 1 >/dev/null; $H/bin/bench dropruns --dialect mariadb ) >>"$LOG" 2>&1; }
  grep -q 'ids=uuidv7' "$MARK" 2>/dev/null && { log "ABORT v7 marker"; echo "v7 marker before $cell" >> $FAIL; exit 4; }
  log "CELL $cell load $(cut -d' ' -f1-3 /proc/loadavg) free $(free_gb)G"
  LANE=$lane RUN=opt2 RESULTS=$RR ORDER=forward VARIANTS="$V" MINFREE_GB=12 BENCH_ARGS="$ba" $H/bin/run-cells.sh "$cell" >/dev/null 2>&1; local x=$?
  [ $x -ne 0 ] && log "run-cells exit $x for $cell"
  [ $x -eq 3 ] && { echo "ABORT disk at $cell" >> $FAIL; exit 3; }
  return 0; }
mkdir -p $RR/mdb-opt2 $RR/mdb-final
for v in mdb-opt2 mdb-final; do printf 'ids=v4\nHARNESS_ID_VERSION=unset\n' > $RR/$v/ids.txt; done
if [ ! -f $ST/equiv.done ]; then
  rm -f $H/build/snap-mariadb-L2.txt; log "##### equiv (lane 2)"
  LANE=2 RUN=opt2-equiv RESULTS=$RR MINFREE_GB=15 VARIANTS="$V" $H/bin/run-cells.sh equiv:mdb-opt2+mdb-final:S1 equiv:mdb-opt2+mdb-final:S5 equiv:mdb-opt2+mdb-final:S7 >/dev/null 2>&1
  grep -q uuidv7 $H/build/snap-mariadb-L2.txt && echo "ABORT: v7 snapshot L2" >> $FAIL
  touch $ST/equiv.done; log "##### equiv DONE"; fi
bench2(){ local lab=$1 v pk=""
  case "$lab" in S4) pk="--pages-keys-file $XR/s4-pages-keys.txt";; esac
  for v in mdb-opt2 mdb-final; do rc 1 "bench:$v:$lab" "--groups HOT,WRITE,MON --query-limit-s 60 $pk"; done; }
conc2(){ local lab=$1 ca v
  case "$lab" in S10-S2-p1-unthr) ca="--pollers 1 --seconds 60 --rate 0";; S10-S2-p4-unthr) ca="--pollers 4 --seconds 60 --rate 0";; S10-S4-p4) ca="--pollers 4 --seconds 60 --rate 500";; esac
  for v in mdb-opt2 mdb-final; do CONC_ARGS="$ca" rc 1 "concurrency:$v:$lab" ""; done; }
wc2(){ for v in mdb-opt2 mdb-final; do rc 1 "writecost:$v:$1" ""; done; }
log "##### timed cells (load $(cut -d' ' -f1-3 /proc/loadavg))"
for l in S8-S2 S16-S2; do bench2 $l; done
wc2 S2-wc
for v in mdb-opt2 mdb-final; do CHURN_ARGS="--retention-max-s 2700" rc 1 "churn:$v:S9" "--groups HOT,WRITE,MON --query-limit-s 60"; done
for l in S10-S2-p1-unthr S10-S2-p4-unthr; do conc2 $l; done
for l in S8-S4 S16-S4 S4; do bench2 $l; done
wc2 S4-wc; conc2 S10-S4-p4
for l in S8-S5 S5 S7; do bench2 $l; done
log "##### ALL DONE"
