#!/usr/bin/env bash
# Round 2 driver (serial, lane 1; lane 2 idle during timed steps). usage: run-r2.sh all | <v4|v7> <phase>
#   phases: s2wc | s4 | s5 | partb (v4 only)      `all` = v4 s2wc s4 s5 partb, then v7 s2wc s4 s5 (resumable: finished result files are skipped)
# Per scenario block: gen (id version checked), runs base, sp, spc, base2 (reference first and last; each prepared once per block, every label of
# the block benched on that schema, bench rolls back its writes). writecost / concurrency are solo cells with their own prepare.
# Timed steps (bench, writecost, concurrency) wait while results/r2/lane2.busy exists (lane-2 equivalence). Step rerun rule: load > 18 at start.
set -u
H=/home/azamojski/namastack-outbox/docs/db-mariadb/harness
X=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r2
XR=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1
R=/home/azamojski/namastack-outbox/docs/db-mariadb/results/r2
OPT=/home/azamojski/outbox-wt-mdb-opt
MARK=$H/build/snap-mariadb-L1.txt
LOG=$R/logs/r2.log; FAIL=$R/failures.txt; mkdir -p $R/logs
cd "$H"; source bin/lane.sh 1 >/dev/null || exit 1
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
load(){ cut -d' ' -f1 /proc/loadavg; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
IDV=""; 
setidv(){ IDV=$1; if [ "$IDV" = v7 ]; then export HARNESS_ID_VERSION=7; else unset HARNESS_ID_VERSION; fi; }
snap_ids(){ if grep -q 'ids=uuidv7' "$MARK" 2>/dev/null; then echo v7; else echo v4; fi; }
snap_sc(){ sed -n 's/^scenario=\(.*\)$/\1/p' "$MARK" 2>/dev/null; }
runname(){ echo "r2-$1-$IDV"; }              # base|sp|spc|base2
ddl_of(){ case "$1" in base|base2) echo $X/base/ddl.sql;; sp) echo $X/sp/ddl.sql;; spc) echo $X/spc/ddl.sql;; esac; }
vargs(){ echo "--variant $(runname $1) --worktree $OPT --catalog-variant mdb-opt --ddl $(ddl_of $1) --queries $X/forms-a/queries"; }
timed_gate(){ local n=0; while [ -f $R/lane2.busy ]; do [ $n = 0 ] && log "waiting for lane 2 (equivalence) before a timed step"; n=1; sleep 20; done; }
step(){ # step <timed 0|1> <desc> <donefile|-> cmd...
  local t=$1 d=$2 done=$3; shift 3
  if [ "$done" != "-" ] && [ -f "$done" ]; then log "SKIP (done) $d"; return 0; fi
  [ "$t" = 1 ] && timed_gate
  local l; l=$(load)
  if awk "BEGIN{exit !($l>18)}"; then log "load $l > 18 at start of: $d (waiting up to 10 min)"; for i in $(seq 1 20); do sleep 30; l=$(load); awk "BEGIN{exit !($l<=18)}" && break; done; fi
  [ "$(free_gb)" -lt 15 ] && { log "low disk: dropruns"; bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
  [ "$(free_gb)" -lt 12 ] && { log "ABORT low disk"; echo "ABORT low disk before $d" >> $FAIL; exit 3; }
  log "RUN $d [ids=$IDV snap=$(snap_ids)/$(snap_sc)] (load $(cut -d' ' -f1-3 /proc/loadavg), free $(free_gb)G)"
  "$@" >>"$LOG" 2>&1; local rc=$?
  [ $rc -ne 0 ] && { log "FAILED rc=$rc: $d"; echo "FAILED rc=$rc: $d (ids $IDV, load at start $l)" >> $FAIL; }
  return $rc; }
db_up(){ docker ps --format '{{.Names}}' | grep -qx outbox-bench-mariadb-L1 || bin/db.sh start mariadb >>"$LOG" 2>&1; }
gen(){ local sc=$1; db_up
  if [ "$(snap_sc)" != "$sc" ] || [ "$(snap_ids)" != "$IDV" ]; then step 0 "gen $sc ids=$IDV" - bin/bench gen --dialect mariadb --scenario "$sc" || return 1; fi
  [ "$(snap_ids)" = "$IDV" ] && [ "$(snap_sc)" = "$sc" ] || { log "ABORT: snapshot is $(snap_sc)/$(snap_ids), wanted $sc/$IDV"; echo "snapshot mismatch $sc/$IDV" >> $FAIL; return 1; }; }
idtree(){ mkdir -p $R/$(runname $1); printf 'ids=%s\nHARNESS_ID_VERSION=%s\nsnapshot=%s\n' "$IDV" "${HARNESS_ID_VERSION:-unset}" "$(tr '\n' ' ' < $MARK)" > $R/$(runname $1)/ids.txt; }
mysqlq(){ docker exec -i outbox-bench-mariadb-L1 mariadb -uroot -pbench -N -B "$@"; }
runid_check(){ # data check on the run schema: version nibble of the first/last id
  local v; v=$(mysqlq run -e "SELECT CONCAT(SUBSTR(MIN(id),15,1),SUBSTR(MAX(id),15,1)) FROM outbox_record" 2>/dev/null)
  log "run-schema id version nibbles (min,max id): $v"; }
prep(){ idtree $1; step 0 "prepare $(runname $1) $2" - bin/bench prepare --dialect mariadb $(vargs $1) --scenario "$2" --label "$3" --results $R && runid_check; }
owned_of(){ case "$1" in S8-*) echo "--owned mod4";; S16-*) echo "--owned $(seq -s, 0 16 240)";; S5-p16) echo "--owned $(seq -s, 0 16 240)";; S5-p199) echo "--owned $(seq -s, 0 198)";; S5-p200) echo "--owned $(seq -s, 0 199)";; S5-p256) echo "--owned all";; *) echo "";; esac; }
scen_of(){ case "$1" in S8-*) echo "${1#S8-}";; S16-*) echo "${1#S16-}";; *) echo "${1%%-*}";; esac; }
PLANQ=count_partitions,count_statuses,keys_strict,keys_nonstrict,oldest_created,oldest_due_strict,oldest_due_nonstrict
bench1(){ local d=$1 lab=$2 run sc ex lim only="" od; run=$(runname $d); sc=$(scen_of $lab); ex=$(owned_of $lab); od=$R/$run/mariadb/$lab
  case "$lab" in
    S8-S5) lim="--query-limit-s 60"; [ "$d" != base ] && lim="$lim --limit-from $R/$(runname base)/mariadb/$lab/timings.csv --limit-factor 2 --limit-min-s 60" ;;
    *) lim="--query-limit-s 10" ;;
  esac
  [ "$lab" = S4 ] && ex="$ex --pages-keys-file $XR/s4-pages-keys.txt"
  case "$lab" in S5-p*) only="--only $PLANQ";; esac
  [ "$(snap_ids)" = "$IDV" ] || { log "ABORT: snapshot id version $(snap_ids) != $IDV"; echo "id mismatch before bench $run $lab" >> $FAIL; exit 4; }
  step 1 "bench $run $lab" $od/timings.csv bin/bench bench --dialect mariadb $(vargs $d) --scenario $sc --label $lab --results $R --groups HOT,WRITE,MON $lim $ex $only; }
block(){ local sc=$1; shift; local labs=("$@") d lab pending
  gen $sc || return 1
  for d in base sp spc base2; do
    labl=("${labs[@]}"); if [ "$sc" = S5 ] && { [ $d = sp ] || [ $d = spc ]; }; then labl+=(S5-p16 S5-p199 S5-p200 S5-p256); fi
    pending=0; for lab in "${labl[@]}"; do [ -f $R/$(runname $d)/mariadb/$lab/timings.csv ] || pending=1; done
    [ $pending = 1 ] || { log "SKIP block $d $sc (all done)"; continue; }
    prep $d $sc ${labs[0]} || continue
    for lab in "${labl[@]}"; do bench1 $d $lab; done
  done
  bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
wc_one(){ local d=$1 sc=$2 lab=$3 od; od=$R/$(runname $d)/mariadb/$lab
  [ -f $od/writecost.csv ] && { log "SKIP (done) writecost $(runname $d) $lab"; return 0; }
  prep $d $sc $lab && step 1 "writecost $(runname $d) $lab" $od/writecost.csv bin/bench writecost --dialect mariadb $(vargs $d) --scenario $sc --label $lab --results $R; }
s10_one(){ local d=$1 lab=S10-S2-p1-unthr od; od=$R/$(runname $d)/mariadb/$lab
  [ -f $od/concurrency-p1-r0.txt ] && { log "SKIP (done) concurrency $(runname $d)"; return 0; }
  prep $d S2 $lab && step 1 "concurrency $(runname $d) $lab" $od/concurrency-p1-r0.txt bin/bench concurrency --dialect mariadb $(vargs $d) --scenario S2 --label $lab --results $R --pollers 1 --seconds 60 --rate 0 --tag p1-r0; }
phase_s2wc(){ gen S2 || return 1; for d in base sp spc base2; do wc_one $d S2 S2-wc; s10_one $d; done; bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
phase_s4(){ block S4 S8-S4 S16-S4 S4; gen S4 || return 1; for d in base sp spc base2; do wc_one $d S4 S4-wc; done; bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
phase_s5(){ block S5 S8-S5 S5; }
# Part B (v4, S5, base DDL; keys_nonstrict only, strict dropped by user decision): K oldest NEW records of every hot key get next_retry_at = T0 + 1 h; ANALYZE TABLE; bench without --prepare.
partb(){ setidv v4; gen S5 || return 1
  local K v lab od
  for K in 10 100 1000; do
    local done=1; for v in head base; do for lab in S8-S5-nd$K S5-nd$K; do [ -f $R/r2-nd$K-$v-v4/mariadb/$lab/timings.csv ] || done=0; done; done
    [ $done = 1 ] && { log "SKIP (done) part B K=$K"; continue; }
    idtree base
    step 0 "prepare part B K=$K" - bin/bench prepare --dialect mariadb --variant r2-nd$K-head-v4 --worktree $OPT --catalog-variant mdb-opt --ddl $X/base/ddl.sql --scenario S5 --label S5-nd$K --results $R || continue
    mysqlq run -e "SET time_zone='+00:00'; UPDATE outbox_record r JOIN (SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) rn FROM outbox_record WHERE status='NEW' AND record_key IN (SELECT record_key FROM (SELECT record_key FROM outbox_record WHERE status='NEW' GROUP BY record_key HAVING COUNT(*) >= 1000) hk)) x WHERE rn <= $K) t ON r.id = t.id SET r.next_retry_at = '2026-01-01 13:00:00'; SELECT ROW_COUNT();" >>"$LOG" 2>&1
    mysqlq run -e "ANALYZE TABLE outbox_record" >>"$LOG" 2>&1
    log "part B K=$K: nonDueNew=$(mysqlq run -e "SELECT COUNT(*) FROM outbox_record WHERE status='NEW' AND next_retry_at > '2026-01-01 12:00:00'") (updated rows set to T0+1h; initial future heads 20)"
    for lab in S8-S5-nd$K S5-nd$K; do
      for v in head base; do
        local ov="" ; [ $v = head ] && ov="--queries $X/forms-a/queries"
        local lim="--query-limit-s 10"; [ $lab = S8-S5-nd$K ] && lim="--query-limit-s 60"
        local ex="--owned all"; [ $lab = S8-S5-nd$K ] && ex="--owned mod4"
        od=$R/r2-nd$K-$v-v4/mariadb/$lab
        step 1 "bench part B r2-nd$K-$v $lab" $od/timings.csv bin/bench bench --dialect mariadb --variant r2-nd$K-$v-v4 --worktree $OPT --catalog-variant mdb-opt --ddl $X/base/ddl.sql $ov --scenario S5 --label $lab --results $R --groups HOT --only keys_nonstrict $lim $ex
      done
    done
  done
  bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
doall(){ setidv v4; phase_s2wc; phase_s4; phase_s5; partb; setidv v7; phase_s2wc; phase_s4; phase_s5; }
log "##### run-r2 $* (sha opt $(git -C $OPT rev-parse --short HEAD), load $(cut -d' ' -f1-3 /proc/loadavg))"
case "${1:-}" in
  all) doall ;;
  v4|v7) setidv $1; case "${2:-}" in s2wc) phase_s2wc;; s4) phase_s4;; s5) phase_s5;; partb) partb;; *) echo "phase?"; exit 1;; esac ;;
  *) echo "usage"; exit 1 ;;
esac
log "##### run-r2 $* DONE"
