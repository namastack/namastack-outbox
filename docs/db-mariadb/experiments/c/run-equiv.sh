#!/usr/bin/env bash
# Confirm-stage equivalence (lane 2 via harness/bin/run-cells.sh): c-1 and c-2 against mdb-opt on S1, S2, S5, S7 (HARNESS_SAMPLE_ORDERED=1 set by run-cells). UUIDv4 only.
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; X=$D/experiments; OPT=/home/azamojski/outbox-wt-mdb-opt
unset HARNESS_ID_VERSION
rm -f $D/harness/build/snap-mariadb-L2.txt   # force a v4 gen (round-2 snapshots may be v7)
R=$D/results/c; mkdir -p $R/logs; echo "ids=v4 HARNESS_ID_VERSION=unset" > $R/equiv-ids.txt
V="mdb-opt=$OPT c-1=$OPT,ddl=$X/r2/spc/ddl.sql,queries=$X/r2/forms-a/queries,catalog=mdb-opt c-2=$OPT,ddl=$X/c/c-2/ddl.sql,queries=$X/r2/forms-e5h/queries,catalog=mdb-opt"
CELLS=""; for s in S1 S2 S5 S7; do CELLS="$CELLS equiv:mdb-opt+c-1:$s equiv:mdb-opt+c-2:$s"; done
LANE=2 RUN=c-equiv RESULTS=$R MINFREE_GB=15 VARIANTS="$V" $D/harness/bin/run-cells.sh $CELLS
grep -q uuidv7 $D/harness/build/snap-mariadb-L2.txt && echo "ABORT: v7 snapshot in L2" >> $R/failures.txt
