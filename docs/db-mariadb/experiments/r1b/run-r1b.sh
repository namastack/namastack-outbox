#!/usr/bin/env bash
# Round 1b driver (serial, lane 1; lane 2 idle). usage: run-r1b.sh <phase>   phases: s4 | s5 | s6 | s7 | s2wc | m5
# Per scenario block: gen once; b-base (own prepare), the DDL runs b-e5l / b-m4 (own prepare each), then a second prepare of the b-base schema
# on which the packs run (no re-prepare between packs; bench rolls back its writes) and, last, b-base2 on that same schema (drift check).
# Writecost/concurrency are solo cells with their own prepare. Rerun a step only when the load at step start is > 18 (waits up to 10 min).
set -u
H=/home/azamojski/namastack-outbox/docs/db-mariadb/harness
X=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1b
XR=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1
R=/home/azamojski/namastack-outbox/docs/db-mariadb/results/r1b
OPT=/home/azamojski/outbox-wt-mdb-opt
LOG=$R/logs/r1b.log; FAIL=$R/failures.txt; mkdir -p $R/logs
cd "$H"; source bin/lane.sh 1 >/dev/null || exit 1
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
load(){ cut -d' ' -f1 /proc/loadavg; }
PACKS="b-qf1 b-qf2 b-qf3 b-qf4 b-qf5 b-qf6 b-qf7 b-qf8 b-qf9 b-m1q"
# run name -> ddl / queries dir
ddl_of(){ case "$1" in b-base|b-base2|b-qf*|b-m1q) echo "$X/b-base/ddl.sql";; b-e5l) echo "$X/b-e5l/ddl.sql";; b-m4) echo "$X/b-m4/ddl.sql";; esac; }
qry_of(){ case "$1" in b-qf*|b-m1q|b-e5l) echo "$X/$1/queries";; esac; }
only_of(){ case "$1" in b-qf*|b-m1q) ls "$X/$1/queries" | sed 's/\.sql$//' | paste -sd, -;; esac; }
vargs(){ local n=$1 a="--variant $1 --worktree $OPT --catalog-variant mdb-opt --ddl $(ddl_of $1)"; [ -n "$(qry_of $n)" ] && a+=" --queries $(qry_of $n)"; echo "$a"; }
step(){ local d=$1; shift; local l; l=$(load)
  if awk "BEGIN{exit !($l>18)}"; then log "load $l > 18 at start of: $d (waiting up to 10 min)"; for i in $(seq 1 20); do sleep 30; l=$(load); awk "BEGIN{exit !($l<=18)}" && break; done; fi
  [ "$(df -BG --output=avail / | tail -1 | tr -dc 0-9)" -lt 15 ] && { log "low disk: dropruns"; bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
  [ "$(df -BG --output=avail / | tail -1 | tr -dc 0-9)" -lt 12 ] && { log "ABORT low disk"; echo "ABORT low disk before $d" >> $FAIL; exit 3; }
  log "RUN $d (load $(cut -d' ' -f1-3 /proc/loadavg), free $(df -BG --output=avail / | tail -1 | tr -dc 0-9)G)"
  "$@" >>"$LOG" 2>&1; local rc=$?
  [ $rc -ne 0 ] && { log "FAILED rc=$rc: $d"; echo "FAILED rc=$rc: $d (load at start $l)" >> $FAIL; }
  return $rc; }
gen(){ local sc=$1 cur; cur="$(sed -n 's/.*scenario=\([^ ]*\).*/\1/p' "$H/build/snap-mariadb-L1.txt" 2>/dev/null)"; [ "$cur" = "$sc" ] || step "gen $sc" bin/bench gen --dialect mariadb --scenario "$sc"; }
prep(){ step "prepare $1 $2" bin/bench prepare --dialect mariadb $(vargs "$1") --scenario "$2" --label "$3" --results "$R"; }
owned_of(){ case "$1" in S8-*) echo "--owned mod4";; S16-*) echo "--owned $(seq -s, 0 16 240)";; *) echo "";; esac; }
scen_of(){ case "$1" in S8-*) echo "${1#S8-}";; S16-*) echo "${1#S16-}";; *) echo "${1%%-*}";; esac; }
bench1(){ local run=$1 lab=$2 sc ex lim only="" o; sc=$(scen_of $lab); ex=$(owned_of $lab)
  case "$lab" in
    S8-S5|S8-S7) lim="--query-limit-s 60"; case "$run" in b-base) ;; *) lim="$lim --limit-from $R/b-base/mariadb/$lab/timings.csv --limit-factor 2 --limit-min-s 60";; esac ;;
    *) lim="--query-limit-s 10" ;;
  esac
  [ "$lab" = S4 ] && ex="$ex --pages-keys-file $XR/s4-pages-keys.txt"
  o=$(only_of $run); [ -n "$o" ] && only="--only $o"
  step "bench $run $lab" bin/bench bench --dialect mariadb $(vargs $run) --scenario $sc --label $lab --results $R --groups HOT,WRITE,MON $lim $ex $only; }
block(){ local sc=$1; shift; local labs=("$@") run lab
  gen $sc || return 1
  for run in b-base b-e5l b-m4; do
    prep $run $sc ${labs[0]}; for lab in "${labs[@]}"; do bench1 $run $lab; done
  done
  prep b-base2 $sc ${labs[0]}
  for run in $PACKS b-base2; do for lab in "${labs[@]}"; do bench1 $run $lab; done; done
  bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1; }
wc_one(){ local run=$1 sc=$2 lab=$3
  prep $run $sc $lab && step "writecost $run $lab" bin/bench writecost --dialect mariadb $(vargs $run) --scenario $sc --label $lab --results $R; }
s10_one(){ local run=$1
  prep $run S2 S10-S2-p1-unthr && step "concurrency $run S10-S2-p1-unthr" bin/bench concurrency --dialect mariadb $(vargs $run) --scenario S2 --label S10-S2-p1-unthr --results $R --pollers 1 --seconds 60 --rate 0 --tag p1-r0; }
log "##### phase $1 (sha opt $(git -C $OPT rev-parse --short HEAD), load $(cut -d' ' -f1-3 /proc/loadavg))"
case "$1" in
  s4) block S4 S8-S4 S16-S4 S4; for run in b-base b-e5l b-m4 b-base2; do wc_one $run S4 S4-wc; done; bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1 ;;
  s5) block S5 S8-S5 S5 ;;
  s6) block S6 S6 ;;
  s7) block S7 S8-S7 S7 ;;
  s2wc) gen S2; for run in b-base b-e5l b-m4 b-base2; do wc_one $run S2 S2-wc; s10_one $run; done; bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1 ;;
  *) echo "unknown phase"; exit 1 ;;
esac
log "##### phase $1 DONE"
