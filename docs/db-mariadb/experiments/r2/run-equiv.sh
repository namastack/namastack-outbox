#!/usr/bin/env bash
# Round 2 equivalence driver (lane 2, via harness/bin/run-cells.sh). usage: run-equiv.sh v4|v7
# v4: forms-a (base ddl) S1,S5 ; forms-a on sp/spc ddl S1 ; forms-e5h (b-e5l ddl) S1,S5,S7.   v7: forms-a S1,S5 ; forms-e5h not run (spec: v4).
# Creates results/r2/lane2.busy while running; the lane-1 driver does not start a timed step while it exists.
set -u
IDV="${1:?v4|v7}"; D=/home/azamojski/namastack-outbox/docs/db-mariadb; X=$D/experiments/r2; OPT=/home/azamojski/outbox-wt-mdb-opt
R=$D/results/r2/equiv-$IDV; mkdir -p "$R"; BUSY=$D/results/r2/lane2.busy
touch "$BUSY"; trap 'rm -f "$BUSY"' EXIT
if [ "$IDV" = v7 ]; then export HARNESS_ID_VERSION=7; rm -f $D/harness/build/snap-mariadb-L2.txt; else unset HARNESS_ID_VERSION; grep -q uuidv7 $D/harness/build/snap-mariadb-L2.txt 2>/dev/null && rm -f $D/harness/build/snap-mariadb-L2.txt; fi
echo "ids=$IDV HARNESS_ID_VERSION=${HARNESS_ID_VERSION:-unset}" > "$R/ids.txt"
V="mdb-opt=$OPT r2-fa=$OPT,ddl=$X/base/ddl.sql,queries=$X/forms-a/queries,catalog=mdb-opt r2-fa-sp=$OPT,ddl=$X/sp/ddl.sql,queries=$X/forms-a/queries,catalog=mdb-opt r2-fa-spc=$OPT,ddl=$X/spc/ddl.sql,queries=$X/forms-a/queries,catalog=mdb-opt r2-e5h=$OPT,ddl=$D/experiments/r1b/b-e5l/ddl.sql,queries=$X/forms-e5h/queries,catalog=mdb-opt"
if [ "$IDV" = v4 ]; then CELLS="equiv:mdb-opt+r2-fa:S1 equiv:mdb-opt+r2-fa-sp:S1 equiv:mdb-opt+r2-fa-spc:S1 equiv:mdb-opt+r2-e5h:S1 equiv:mdb-opt+r2-fa:S5 equiv:mdb-opt+r2-e5h:S5 equiv:mdb-opt+r2-e5h:S7"
else CELLS="equiv:mdb-opt+r2-fa:S1 equiv:mdb-opt+r2-fa:S5"; fi
LANE=2 RUN=r2-equiv-$IDV RESULTS=$R VARIANTS="$V" $D/harness/bin/run-cells.sh $CELLS
