#!/usr/bin/env bash
# One rerun of the cells with pass1/pass2 spread > 2x threshold (mdb-main S4, S4-b100), results/final/rerun1. Lane 1 solo.
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; R=$D/results/final/rerun1; unset HARNESS_ID_VERSION
mkdir -p $R/mdb-main; printf 'ids=v4\nHARNESS_ID_VERSION=unset\n' > $R/mdb-main/ids.txt
for lab in S4 S4-b100; do
  pk=$D/experiments/r1/s4-pages-keys.txt; [ $lab = S4-b100 ] && pk=$D/experiments/r1/s4-pages-keys-b100.txt
  echo "[$(date)] rerun main $lab load $(cut -d' ' -f1-3 /proc/loadavg)" >> $D/results/final/logs/driver.log
  LANE=1 RUN=final-rerun RESULTS=$R VARIANTS="mdb-main=/home/azamojski/outbox-wt-mdb-main,catalog=mdb-main" MINFREE_GB=12 BENCH_ARGS="--groups HOT,WRITE,MON --query-limit-s 120 --pages-keys-file $pk" $H/bin/run-cells.sh bench:mdb-main:$lab >/dev/null 2>&1
done
echo "[$(date)] rerun DONE" >> $D/results/final/logs/driver.log
