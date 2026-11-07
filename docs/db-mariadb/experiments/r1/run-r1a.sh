#!/usr/bin/env bash
# Round 1a driver (serial, lane 1). usage: run-r1a.sh <phase>   phases: s2 | s4 | s5 | s6 | s7 | equiv1
# Per scenario block: for each run the run schema is prepared once, then every label of that scenario is benched on it
# (bench rolls back its writes); the reference runs first (r1-ref) and last (r1-ref2). Writecost/concurrency are solo cells with their own prepare.
set -u
H=/home/azamojski/namastack-outbox/docs/db-mariadb/harness
X=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1
R=/home/azamojski/namastack-outbox/docs/db-mariadb/results/r1a
OPT=/home/azamojski/outbox-wt-mdb-opt; MAIN=/home/azamojski/outbox-wt-mdb-main
LOG=$R/logs/r1a.log; FAIL=$R/failures.txt; mkdir -p $R/logs
cd "$H"; source bin/lane.sh 1 >/dev/null || exit 1
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
load(){ cut -d' ' -f1 /proc/loadavg; }
# run name -> "ddl|queries" (empty = branch / none)
declare -A DDL QRY
DDL[r1-ref]=""; DDL[r1-ref2]=""
for r in m1d e3 a1 e1b e4 e5q e2q; do DDL[r1-$r]="$X/r1-$r/ddl.sql"; done
DDL[r1-e4q]="$X/r1-e4/ddl.sql"; DDL[r1-e5c]="$X/r1-e5q/ddl.sql"
for r in e4q e5q e5c e2q; do QRY[r1-$r]="$X/r1-$r/queries"; done
declare -A ONLY; ONLY[r1-e4q]="oldest_created,oldest_due_nonstrict"; ONLY[r1-e5c]="oldest_created,oldest_due_nonstrict,keys_nonstrict"
SHARE_PREV() { case "$1" in r1-e4q|r1-e5c) return 0;; *) return 1;; esac; }   # same DDL as the previous run: no new prepare
vargs(){ local n=$1 a="--variant $1 --worktree $OPT --catalog-variant mdb-opt"; [ -n "${DDL[$n]}" ] && a+=" --ddl ${DDL[$n]}"; [ -n "${QRY[$n]:-}" ] && a+=" --queries ${QRY[$n]}"; echo "$a"; }
step(){ # step <desc> cmd...
  local d=$1; shift; local l; l=$(load)
  if awk "BEGIN{exit !($l>18)}"; then log "load $l > 18 at start of: $d (waiting up to 10 min)"; for i in $(seq 1 20); do sleep 30; l=$(load); awk "BEGIN{exit !($l<=18)}" && break; done; fi
  [ "$(df -BG --output=avail / | tail -1 | tr -dc 0-9)" -lt 12 ] && { log "ABORT low disk"; echo "ABORT low disk before $d" >> $FAIL; exit 3; }
  log "RUN $d (load $(cut -d' ' -f1-3 /proc/loadavg), free $(df -BG --output=avail / | tail -1 | tr -dc 0-9)G)"
  "$@" >>"$LOG" 2>&1; local rc=$?
  [ $rc -ne 0 ] && { log "FAILED rc=$rc: $d"; echo "FAILED rc=$rc: $d (load at start $l)" >> $FAIL; }
  return $rc; }
gen(){ local sc=$1 cur; cur="$(sed -n 's/.*scenario=\([^ ]*\).*/\1/p' "$H/build/snap-mariadb-L1.txt" 2>/dev/null)"; [ "$cur" = "$sc" ] || step "gen $sc" bin/bench gen --dialect mariadb --scenario "$sc"; }
prep(){ step "prepare $1 $2" bin/bench prepare --dialect mariadb $(vargs "$1") --scenario "$2" --label "$3" --results "$R"; }
owned_of(){ case "$1" in S8-*) echo "--owned mod4";; S16-*) echo "--owned $(seq -s, 0 16 240)";; *) echo "";; esac; }
scen_of(){ case "$1" in S8-*) echo "${1#S8-}";; S16-*) echo "${1#S16-}";; *) echo "${1%%-*}";; esac; }
bench1(){ local run=$1 lab=$2 sc ex lim only=""; sc=$(scen_of $lab); ex=$(owned_of $lab)
  case "$lab" in
    S8-S5|S8-S7) lim="--query-limit-s 60"; [ "$run" != r1-ref ] && lim="$lim --limit-from $R/r1-ref/mariadb/$lab/timings.csv --limit-factor 2 --limit-min-s 60" ;;
    *) lim="--query-limit-s 10" ;;
  esac
  [ "$lab" = S4 ] && [ -f $X/s4-pages-keys.txt ] && ex="$ex --pages-keys-file $X/s4-pages-keys.txt"
  [ -n "${ONLY[$run]:-}" ] && only="--only ${ONLY[$run]}"
  step "bench $run $lab" bin/bench bench --dialect mariadb $(vargs $run) --scenario $sc --label $lab --results $R --groups HOT,WRITE,MON $lim $ex $only; }
block(){ local sc=$1; shift; local labs=("$@") run lab
  gen $sc || return 1
  for run in r1-ref r1-m1d r1-e3 r1-a1 r1-e1b r1-e4 r1-e4q r1-e5q r1-e5c r1-e2q r1-ref2; do
    SHARE_PREV $run || prep $run $sc ${labs[0]}
    if [ "$sc" = S4 ] && [ ! -f $X/s4-pages-keys.txt ]; then   # fallback keys (see report): first 10 distinct keys of due NEW rows in created_at order
      docker exec -i outbox-bench-mariadb-L1 mariadb -uroot -pbench -N -B run -e "SET time_zone='+00:00'; SELECT record_key FROM outbox_record WHERE status='NEW' AND completed_at IS NULL AND next_retry_at <= '2026-01-01 12:00:00' ORDER BY created_at, id LIMIT 200" | awk '!s[$0]++' | head -10 > $X/s4-pages-keys.txt
      log "s4-pages-keys.txt: $(wc -l < $X/s4-pages-keys.txt) keys"; fi
    for lab in "${labs[@]}"; do bench1 $run $lab; done
    # same-DDL query variants run right after, on the same schema, before the next prepare
  done; }
wc_s2(){ local run=$1; prep $run S2 S2-wc && step "writecost $run S2-wc" bin/bench writecost --dialect mariadb $(vargs $run) --scenario S2 --label S2-wc --results $R
  prep $run S2 S10-S2-p1-unthr && step "concurrency $run S10-S2-p1-unthr" bin/bench concurrency --dialect mariadb $(vargs $run) --scenario S2 --label S10-S2-p1-unthr --results $R --pollers 1 --seconds 60 --rate 0 --tag p1-r0; }
wc_s4(){ for run in r1-ref r1-m1d r1-e3 r1-a1 r1-e1b r1-e4 r1-e5q r1-e2q r1-ref2; do prep $run S4 S4-wc && step "writecost $run S4-wc" bin/bench writecost --dialect mariadb $(vargs $run) --scenario S4 --label S4-wc --results $R; done; }
log "##### phase $1 (sha opt $(git -C $OPT rev-parse --short HEAD), load $(cut -d' ' -f1-3 /proc/loadavg))"
case "$1" in
  s2) gen S2; for run in r1-ref r1-m1d r1-e3 r1-a1 r1-e1b r1-e4 r1-e5q r1-e2q r1-ref2; do wc_s2 $run; done ;;
  s4) block S4 S8-S4 S16-S4 S4; wc_s4 ;;
  s5) block S5 S8-S5 S5 ;;
  s6) block S6 S6 ;;
  s7) block S7 S8-S7 S7 ;;
  *) echo "unknown phase"; exit 1 ;;
esac
log "##### phase $1 DONE"
