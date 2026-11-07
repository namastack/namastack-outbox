#!/usr/bin/env bash
set -u
H=/home/azamojski/namastack-outbox/docs/db-mariadb/harness; X=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1; R=/home/azamojski/namastack-outbox/docs/db-mariadb/results/r1a
OPT=/home/azamojski/outbox-wt-mdb-opt
cd $H; source bin/lane.sh 1 >/dev/null
eq(){ sc=$1; run=$2; ddl=$3; q=$4
  a="--b $run --worktree-b $OPT --catalog-variant-b mdb-opt --queries-b $X/$q"; [ -n "$ddl" ] && a="$a --ddl-b $X/$ddl/ddl.sql"
  echo "equiv $sc $run load $(cut -d' ' -f1-3 /proc/loadavg)"
  HARNESS_SAMPLE_ORDERED=1 bin/bench equiv --dialect mariadb --a mdb-opt --worktree-a $OPT $a --scenario $sc --results $R || echo "equiv $sc $run exit $?" >> $R/failures.txt; }
for sc in S5 S1; do
  [ $sc = S5 ] && { bin/bench gen --dialect mariadb --scenario S5 >/dev/null 2>&1; $X/m8.sh S5; }
  [ $sc = S1 ] && bin/bench gen --dialect mariadb --scenario S1 >/dev/null 2>&1
  eq $sc r1-e4q r1-e4 r1-e4q; eq $sc r1-e5q r1-e5q r1-e5q; eq $sc r1-e5c r1-e5q r1-e5c; eq $sc r1-e2q r1-e2q r1-e2q
done
echo TAIL DONE
