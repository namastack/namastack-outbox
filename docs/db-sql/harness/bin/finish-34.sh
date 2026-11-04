#!/usr/bin/env bash
# Driver: finishes phases 3/4 (main + v1) on the non-PostgreSQL dialects. Serial, resumable, one dialect's failure does not stop the others.
# usage: [DIALECTS="h2 mariadb mysql sqlserver oracle"] [RESULTS=dir] [LABELSUFFIX=] [SCENARIOS="S1 S2 S4 S5 S7"] [REDO=1] bin/finish-34.sh
#
# Per dialect and scenario: gen (only when a cell of the scenario is missing), bench main + v1 (--prepare), S8-* labels (S2, S4),
# S2: writecost per variant, equivalence (EQUIV_SCEN, default "S1 S5": equivalence holds a snapshot and two copies, so S2 filled the disk earlier and S4 is larger). A step is skipped when its result file exists (REDO=1 repeats).
# Cells: S1 | S2, S8-S2, writecost(S2) | S4, S8-S4 | S5, equiv S5 | S7. Finished cells (results/<v>/<d>/<label>/timings.csv, writecost.csv, results/equiv/<d>/<S>/main-vs-v1.md) are skipped.
# Logs: results/logs/finish34.log (driver), finish34.out (tool output), finish34-failures.txt. Abort (exit 3) when free disk < MINFREE_GB (default 5).
# Slow queries: the harness caps a query whose first execution takes > 30 s (1 execution, non-executing plan); each step also has a wall-clock
# `timeout` (STEP_TIMEOUT_H, default 6 h; gen 2 h).
#
# Runtime estimate (hours, main + v1, serial; from results/logs/matrix-{mysql,mariadb}.log step times, other dialects extrapolated):
#   h2        ~1-2     (in-process; extrapolated, no earlier run)
#   mariadb   ~6-8     (S2 prepare+bench was 26-43 min per variant before deferred indexes; S4/S5/S7 similar)
#   mysql     ~8-12    (main retention queries ~285 s per execution; S2 main 1h50 in the stopped run)
#   sqlserver ~6-10    (extrapolated, batch-insert gen)
#   oracle    ~6-10    (extrapolated, batch-insert gen)
#   total     ~27-42 h
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"
R="${RESULTS:-$H/../results}"; mkdir -p "$R"; R="$(cd "$R" && pwd)"; L="$R/logs"; mkdir -p "$L/state"
WT_main=/home/zamo/git/outbox-wt-main; WT_v1=/home/zamo/git/outbox-wt-v1
MINFREE_GB=${MINFREE_GB:-5}; STEP_T=$(( ${STEP_TIMEOUT_H:-6} * 3600 )); GEN_T=7200
SFX="${LABELSUFFIX:-}"
# User 2026-11-04: main only; v1 lost to the phase 5 sets, so phase 6 compares main with the candidates directly. VARIANTS="main v1" EQUIV_SCEN="S1 S5" restores v1 and its main-vs-v1 equivalence.
VS="${VARIANTS:-main}"; EQUIV_SCEN="${EQUIV_SCEN:-}"
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$L/finish34.log"; }
free_gb(){ df -BG --output=avail / | tail -1 | tr -dc 0-9; }
step(){ # step <dialect> <done-file|-> <timeout> cmd...
  local d=$1 done=$2 t=$3; shift 3
  if [ "$done" != "-" ] && [ -f "$done" ] && [ "${REDO:-0}" != 1 ]; then log "SKIP (done) $d: ${done#$R/}"; return 0; fi
  if [ "$(free_gb)" -lt "$MINFREE_GB" ]; then log "ABORT: free disk $(free_gb) GB < $MINFREE_GB GB"; echo "ABORT disk $(free_gb)G before: $*" >> "$L/finish34-failures.txt"; exit 3; fi
  # MySQL keeps its default binary log, unchanged for comparability with the earlier cells; old log files are only removed from disk between steps.
  [ "$d" = mysql ] && docker exec outbox-bench-mysql mysql -uroot -pbench -e "PURGE BINARY LOGS BEFORE NOW()" >>"$L/finish34.out" 2>&1
  log "RUN($d, free $(free_gb)G): $*"
  timeout "$t" "$@" >>"$L/finish34.out" 2>&1; local rc=$?
  [ $rc -ne 0 ] && { log "FAILED rc=$rc ($d): $*"; echo "FAILED($d) rc=$rc: $*" >> "$L/finish34-failures.txt"; }
  return $rc; }
va(){ local w=WT_$1; echo "--variant $1 --worktree ${!w}"; }
tim(){ echo "$R/$1/$DL/$2$SFX/timings.csv"; }
needs(){ # scenario -> 0 when some step of it is missing
  local sc=$1 v lab
  for lab in $(labels $sc); do for v in $VS; do [ -f "$(tim $v $lab)" ] || return 0; done; done
  if [ $sc = S2 ]; then for v in $VS; do [ -f "$R/$v/$DL/S2$SFX/writecost.csv" ] || return 0; done; fi
  [[ " $EQUIV_SCEN " == *" $sc "* ]] && { [ -f "$R/equiv/$DL/$sc$SFX/main-vs-v1.md" ] || return 0; }
  return 1; }
labels(){ case $1 in S2) echo "S2 S8-S2";; S4) echo "S4 S8-S4";; *) echo $1;; esac; }
doscen(){ local sc=$1 lab v ex
  needs $sc || { log "$DL $sc: all cells done"; return 0; }
  log "=== $DL $sc gen"
  rm -f "$L/state/snap-$DL"
  step $DL - $GEN_T bin/bench gen --dialect $DL --scenario $sc || { log "$DL $sc: gen failed, scenario skipped"; return 1; }
  echo $sc > "$L/state/snap-$DL"
  for lab in $(labels $sc); do ex=""; [[ $lab == S8-* ]] && ex="--owned mod4 --groups HOT,WRITE,MON"
    for v in $VS; do
      step $DL "$(tim $v $lab)" $STEP_T bin/bench bench --dialect $DL $(va $v) --scenario $sc --label "$lab$SFX" --prepare --results "$R" $ex; done; done
  if [ $sc = S2 ]; then
    for v in $VS; do [ -f "$R/$v/$DL/S2$SFX/writecost.csv" ] && [ "${REDO:-0}" != 1 ] && continue
      step $DL - $STEP_T bin/bench prepare --dialect $DL $(va $v) --scenario S2 --label "S2$SFX" --results "$R" && \
      step $DL "$R/$v/$DL/S2$SFX/writecost.csv" $STEP_T bin/bench writecost --dialect $DL $(va $v) --scenario S2 --label "S2$SFX" --results "$R" ${WCARGS:-}; done; fi
  if [[ " $EQUIV_SCEN " == *" $sc "* ]]; then
    HARNESS_SAMPLE_ORDERED=1 step $DL "$R/equiv/$DL/$sc$SFX/main-vs-v1.md" $STEP_T bin/bench equiv --dialect $DL --a main --b v1 \
      --worktree-a $WT_main --worktree-b $WT_v1 --scenario $sc --results "$R"; fi
  step $DL - 600 bin/bench dropruns --dialect $DL
  return 0; }
# Disk: free space is about 6 GB with all images and volumes present, and MySQL S2 alone used ~14 GB earlier. Only the current dialect's image and
# volume are kept; the others are removed (benchmark data is regenerated by gen, results live in results/). The postgres image is kept.
image(){ case $1 in mariadb) echo mariadb:12;; mysql) echo mysql:9.2;; sqlserver) echo mcr.microsoft.com/mssql/server:2022-latest;; oracle) echo gvenzl/oracle-xe:18-slim-faststart;; esac; }
reclaim(){ local d; for d in postgres mariadb mysql sqlserver oracle; do [ "$d" = "$1" ] && continue; bin/db.sh wipe $d >>"$L/finish34.out" 2>&1
    [ $d != postgres ] && docker rmi "$(image $d)" >>"$L/finish34.out" 2>&1; done; [ "$1" != h2 ] && rm -rf "$H/build/h2"; log "reclaim before $1: free $(free_gb)G"; }
for DL in ${DIALECTS:-h2 mariadb mysql sqlserver oracle}; do
  log "##### dialect $DL"
  reclaim $DL
  if [ $DL != h2 ]; then docker pull "$(image $DL)" >>"$L/finish34.out" 2>&1 || log "pull failed for $DL"; fi
  if [ $DL != h2 ]; then bin/db.sh start $DL >>"$L/finish34.out" 2>&1 || { log "FAILED: container $DL did not start, dialect skipped"; echo "FAILED($DL) container start" >> "$L/finish34-failures.txt"; continue; }; fi
  for sc in ${SCENARIOS:-S1 S2 S4 S5 S7}; do doscen $sc; done
  [ $DL != h2 ] && bin/db.sh stop $DL >>"$L/finish34.out" 2>&1
  log "DONE $DL"
done
bin/cleanup.sh >>"$L/finish34.out" 2>&1; log "ALL DONE (containers removed; volumes kept)"
