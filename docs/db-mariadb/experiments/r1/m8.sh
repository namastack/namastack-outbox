#!/usr/bin/env bash
# usage: m8.sh <S5|S7>   (snapshot of that scenario must be loaded)
set -u
H=/home/azamojski/namastack-outbox/docs/db-mariadb/harness; X=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1; R=/home/azamojski/namastack-outbox/docs/db-mariadb/results/r1a
cd $H; source bin/lane.sh 1 >/dev/null
sc=$1
for v in r1-ref r1-m1d; do
  a="--variant $v --worktree /home/azamojski/outbox-wt-mdb-opt --catalog-variant mdb-opt"; [ $v = r1-m1d ] && a="$a --ddl $X/r1-m1d/ddl.sql"
  bin/bench prepare --dialect mariadb $a --scenario $sc --schema m8 --results $R >/dev/null 2>&1 || echo "prepare failed $v $sc"
  echo "M8 $sc $v load $(cut -d' ' -f1-3 /proc/loadavg)"
  python3 $X/m8.py $R/m8/$sc/$v $sc $v
done
