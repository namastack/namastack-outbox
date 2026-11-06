#!/usr/bin/env bash
# Driver: current main (cmp-main, f4446c8) vs feature/db-jdbc-optimization (cmp-jdbc-opt, 01165d15) on the non-PostgreSQL dialects, each with its own shipped DDL and SQL. Serial, resumable, one dialect failure does not stop the others. Based on finish-34.sh. NORMI=1 keeps the images of other dialects (validation run).
# usage: [DIALECTS="h2 mariadb mysql sqlserver oracle"] [RESULTS=dir] [LABELSUFFIX=] [SCENARIOS="S1 S2 S4 S5 S7"] [REDO=1] [NORMI=1] bin/cmp-dialects.sh
#
# Per dialect and scenario: gen (only when a cell of the scenario is missing), bench main + v1 (--prepare), S8-* labels (S2, S4),
# S2: writecost per variant, equivalence (EQUIV_SCEN, default "S1 S5": equivalence holds a snapshot and two copies, so S2 filled the disk earlier and S4 is larger). A step is skipped when its result file exists (REDO=1 repeats).
# Cells: S1 | S2, S8-S2, writecost(S2) | S4, S8-S4 | S5, equiv S5 | S7. Finished cells (results/<v>/<d>/<label>/timings.csv, writecost.csv, results/equiv/<d>/<S>/cmp-main-vs-cmp-jdbc-opt.md) are skipped.
# Logs: results/logs/cmpdb.log (driver), cmpdb.out (tool output), cmpdb-failures.txt. Abort (exit 3) when free disk < MINFREE_GB (default 5).
# Slow queries: the harness caps a query whose first execution takes > 30 s (1 execution, non-executing plan); each step also has a wall-clock
# `timeout` (STEP_TIMEOUT_H, default 6 h; gen 2 h).
#
# Template only (from the PostgreSQL study, written for another host): use it as a model for bin/run-cells.sh, do not run it.
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="${RESULTS:-$H/../results}"; mkdir -p "$R"; R="$(cd "$R" && pwd)"; L="$R/logs"; mkdir -p "$L/state"
WT_cmp_main=/home/zamo/git/outbox-wt-cmp-main; WT_cmp_jdbc_opt=/home/zamo/git/outbox-wt-cmp-jdbc-opt
MINFREE_GB=${MINFREE_GB:-5}; STEP_T=$(( ${STEP_TIMEOUT_H:-6} * 3600 )); GEN_T=7200
SFX="${LABELSUFFIX:-}"
VS="${VARIANTS:-cmp-main cmp-jdbc-opt}"; EQUIV_SCEN="${EQUIV_SCEN:-S1 S5}"
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$L/cmpdb.log"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
step(){ # step <dialect> <done-file|-> <timeout> cmd...
  local d=$1 done=$2 t=$3; shift 3
  if [ "$done" != "-" ] && [ -f "$done" ] && [ "${REDO:-0}" != 1 ]; then log "SKIP (done) $d: ${done#$R/}"; return 0; fi
  if [ "$(free_gb)" -lt "$MINFREE_GB" ]; then log "ABORT: free disk $(free_gb) GB < $MINFREE_GB GB"; echo "ABORT disk $(free_gb)G before: $*" >> "$L/cmpdb-failures.txt"; exit 3; fi
  # MySQL keeps its default binary log, unchanged for comparability with the earlier cells; old log files are only removed from disk between steps.
  [ "$d" = mysql ] && docker exec outbox-bench-mysql mysql -uroot -pbench -e "PURGE BINARY LOGS BEFORE NOW()" >>"$L/cmpdb.out" 2>&1
  log "RUN($d, free $(free_gb)G): $*"
  timeout "$t" "$@" >>"$L/cmpdb.out" 2>&1; local rc=$?
  [ $rc -ne 0 ] && { log "FAILED rc=$rc ($d): $*"; echo "FAILED($d) rc=$rc: $*" >> "$L/cmpdb-failures.txt"; }
  return $rc; }
va(){ local w=WT_${1//-/_}; echo "--variant $1 --worktree ${!w}"; }
tim(){ echo "$R/$1/$DL/$2$SFX/timings.csv"; }
needs(){ # scenario -> 0 when some step of it is missing
  local sc=$1 v lab
  for lab in $(labels $sc); do for v in $VS; do [ -f "$(tim $v $lab)" ] || return 0; done; done
  if [ $sc = S2 ]; then for v in $VS; do [ -f "$R/$v/$DL/S2$SFX/writecost.csv" ] || return 0; done; fi
  [[ " $EQUIV_SCEN " == *" $sc "* ]] && { [ -f "$R/equiv/$DL/$sc$SFX/cmp-main-vs-cmp-jdbc-opt.md" ] || return 0; }
  return 1; }
labels(){ case $1 in S2) echo "S2 S8-S2";; S4) echo "S4 S8-S4";; *) echo $1;; esac; }
# Per-query limit (07-next-runs-plan.md): main 900 s; branch max(2 x main median, 60 s), 900 s when main has no median for the query.
limits(){ if [ "$1" = cmp-main ]; then echo "--query-limit-s 900"; else echo "--query-limit-s 900 --limit-from $(tim cmp-main $2) --limit-factor 2 --limit-min-s 60"; fi; }
# H2's file does not shrink, and S4 steps grow it by up to 12 GB: keep the clean file after gen on the T7 disk and restore it before each step. H2 itself runs on the internal disk.
T7DIR=${T7DIR:-/run/media/zamo/T7/outbox-bench}
h2save(){ [ $DL = h2 ] || return 0; if [ -d "$T7DIR" ]; then cp "$H/build/h2/bench.mv.db" "$T7DIR/bench-$1.mv.db" && log "h2: saved clean $1 file to T7"; else log "h2: T7 not mounted, no copy"; fi; }
h2restore(){ [ $DL = h2 ] || return 0; [ -f "$T7DIR/bench-$1.mv.db" ] || { log "h2: no T7 copy for $1, not restored"; return 0; }; rm -f "$H/build/h2/bench.mv.db"; cp "$T7DIR/bench-$1.mv.db" "$H/build/h2/bench.mv.db" && log "h2: restored clean $1 file, free $(free_gb)G"; }
doscen(){ local sc=$1 lab v ex
  needs $sc || { log "$DL $sc: all cells done"; return 0; }
  log "=== $DL $sc gen"
  rm -f "$L/state/snap-$DL"
  [ $DL = h2 ] && rm -f "$H/build/h2/bench.mv.db"
  step $DL - $GEN_T bin/bench gen --dialect $DL --scenario $sc || { log "$DL $sc: gen failed, scenario skipped"; return 1; }
  echo $sc > "$L/state/snap-$DL"
  h2save $sc
  # User 2026-11-04: timings for P1 and P2 groups only, no retention or admin (RET, ADMIN). Equivalence still reads every query once.
  for lab in $(labels $sc); do ex="--groups HOT,WRITE,MON"; [[ $lab == S8-* ]] && ex="--owned mod4 --groups HOT,WRITE,MON"
    for v in $VS; do
      [ -f "$(tim $v $lab)" ] || h2restore $sc
      step $DL "$(tim $v $lab)" $STEP_T bin/bench bench --dialect $DL $(va $v) --scenario $sc --label "$lab$SFX" --prepare --results "$R" $ex $(limits $v $lab); done; done
  if [ $sc = S2 ]; then
    for v in $VS; do [ -f "$R/$v/$DL/S2$SFX/writecost.csv" ] && [ "${REDO:-0}" != 1 ] && continue
      h2restore $sc
      step $DL - $STEP_T bin/bench prepare --dialect $DL $(va $v) --scenario S2 --label "S2$SFX" --results "$R" && \
      step $DL "$R/$v/$DL/S2$SFX/writecost.csv" $STEP_T bin/bench writecost --dialect $DL $(va $v) --scenario S2 --label "S2$SFX" --results "$R" ${WCARGS:-}; done; fi
  if [[ " $EQUIV_SCEN " == *" $sc "* ]]; then
    [ -f "$R/equiv/$DL/$sc$SFX/cmp-main-vs-cmp-jdbc-opt.md" ] || h2restore $sc
    HARNESS_SAMPLE_ORDERED=1 step $DL "$R/equiv/$DL/$sc$SFX/cmp-main-vs-cmp-jdbc-opt.md" $STEP_T bin/bench equiv --dialect $DL --a cmp-main --b cmp-jdbc-opt \
      --worktree-a $WT_cmp_main --worktree-b $WT_cmp_jdbc_opt --scenario $sc --results "$R" --query-limit-s 900; fi
  step $DL - 600 bin/bench dropruns --dialect $DL
  return 0; }
# Disk: free space is about 6 GB with all images and volumes present, and MySQL S2 alone used ~14 GB earlier. Only the current dialect's image and
# volume are kept; the others are removed (benchmark data is regenerated by gen, results live in results/). The postgres image is kept.
image(){ case $1 in mariadb) echo mariadb:12;; mysql) echo mysql:9.2;; sqlserver) echo mcr.microsoft.com/mssql/server:2022-latest;; oracle) echo gvenzl/oracle-xe:18-slim-faststart;; esac; }
reclaim(){ local d; for d in postgres mariadb mysql sqlserver oracle; do [ "$d" = "$1" ] && continue; bin/db.sh wipe $d >>"$L/cmpdb.out" 2>&1
    [ $d != postgres ] && [ "${NORMI:-0}" != 1 ] && docker rmi "$(image $d)" >>"$L/cmpdb.out" 2>&1; done; [ "$1" != h2 ] && rm -rf "$H/build/h2"; log "reclaim before $1: free $(free_gb)G"; }
for DL in ${DIALECTS:-h2 mariadb mysql sqlserver oracle}; do
  log "##### dialect $DL"
  reclaim $DL
  if [ $DL != h2 ]; then docker pull "$(image $DL)" >>"$L/cmpdb.out" 2>&1 || log "pull failed for $DL"; fi
  if [ $DL != h2 ]; then bin/db.sh start $DL >>"$L/cmpdb.out" 2>&1 || { log "FAILED: container $DL did not start, dialect skipped"; echo "FAILED($DL) container start" >> "$L/cmpdb-failures.txt"; continue; }; fi
  if timeout 300 bin/bench limitcheck --dialect $DL --query-limit-s 3 >>"$L/cmpdb.out" 2>&1; then log "limitcheck $DL ok"; else log "limitcheck $DL FAILED: the per-query limit may not stop queries here, only the step limit applies"; echo "limitcheck($DL) failed" >> "$L/cmpdb-failures.txt"; fi
  for sc in ${SCENARIOS:-S1 S2 S4 S5 S7}; do doscen $sc; done
  [ $DL = h2 ] && rm -f "$T7DIR"/bench-*.mv.db && log "h2: copies removed from T7"
  [ $DL != h2 ] && bin/db.sh stop $DL >>"$L/cmpdb.out" 2>&1
  log "DONE $DL"
done
bin/cleanup.sh >>"$L/cmpdb.out" 2>&1; log "ALL DONE (containers removed; volumes kept)"
