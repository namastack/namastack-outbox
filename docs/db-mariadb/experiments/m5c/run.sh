#!/usr/bin/env bash
# M5 follow-up: pages forms under S10. usage: m5c/run.sh equiv|timed
set -u
D=/home/azamojski/namastack-outbox/docs/db-mariadb; H=$D/harness; X=$D/experiments/m5c; R=$D/results/m5c; WF=/home/azamojski/outbox-wt-mdb-final
unset HARNESS_ID_VERSION; mkdir -p $R/logs
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a $R/logs/driver.log; }
VC="mdb-final=$WF,catalog=mdb-final m5c-json=$WF,catalog=mdb-final,queries=$X/json m5c-values=$WF,catalog=mdb-final,queries=$X/values"
VT="m5c-in=$WF,catalog=mdb-final,queries=$X/in m5c-json=$WF,catalog=mdb-final,queries=$X/json m5c-values=$WF,catalog=mdb-final,queries=$X/values"
case $1 in
 equiv) rm -f $H/build/snap-mariadb-L2.txt
  C=""; for s in S1 S5; do C="$C equiv:mdb-final+m5c-json:$s equiv:mdb-final+m5c-values:$s"; done
  LANE=2 RUN=m5c-equiv RESULTS=$R MINFREE_GB=15 VARIANTS="$VC" $H/bin/run-cells.sh $C >/dev/null 2>&1; log "equiv done";;
 timed)
  for lab in S10-S2-p4 S10-S4-p4; do
   for step in 1:m5c-in 2:m5c-json 3:m5c-values 4:m5c-in; do n=${step%%:*}; v=${step#*:}
     log "CELL $v $lab step$n load $(cut -d' ' -f1-3 /proc/loadavg)"
     mkdir -p $R/pass$n
     LANE=1 RUN=pass$n RESULTS=$R/pass$n VARIANTS="$VT" CONC_ARGS="--pollers 4 --seconds 60 --rate 500" $H/bin/run-cells.sh "concurrency:$v:$lab" >/dev/null 2>&1 || log "exit $? $v $lab"
   done; done; log "timed done";;
esac
