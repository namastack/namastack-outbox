#!/usr/bin/env bash
# Runs query files against a running container and records EXPLAIN ANALYZE results.
#
# Usage: run-explain.sh -c <container> -s <scenario-label> [options] [query[@var=val,var=val]]...
#   -c NAME        container (e.g. outbox-db-base-A)
#   -s LABEL       results subdirectory label (results/<LABEL>/), e.g. A, B, H07-B
#   -q DIR         queries directory (default harness/queries). Must contain (or gets a copy of) _params.sql
#   -o DIR         results root (default docs/db/results)
#   -r N           measured runs after 1 warm-up (default 3)
#   --costs        also print costs (EXPLAIN ... COSTS ON); output files get suffix -costs
#   --generic      PREPARE + plan_cache_mode=force_generic_plan (only files with a generic branch:
#                  hot-path and partition queries); output files get suffix -generic
#   --var k=v      extra psql variable for every run (repeatable), e.g. --var limit=500
#   --set k=v      session GUC for every run (repeatable), e.g. --set max_parallel_workers=0
#                  (emulates pgjdbc setMaxRows: the plan keeps Gather but no worker is launched)
#   --tag T        extra suffix for output file names and the summary mode column (e.g. serial)
#   --no-default-variants   ignore the `-- variants:` header lines
#   queries        file names without .sql (default: all files in the queries dir except _*).
#                  `name@parts=256` runs only that variant; `name@` runs the file once with defaults.
#
# Each query file runs in its own psql session: 1 warm-up + N measured runs of
# EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF, SUMMARY ON). Variants come from the file's
# `-- variants: a=1 a=2` header (a variant may set several vars: `parts=64,status=NEW`).
# Raw output (median run, IN lists trimmed): results/<label>/<query>[-<variant>][-generic][-costs].txt
# Summary rows are appended to results/<label>/summary.tsv.
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QDIR="$HARNESS_DIR/queries"
OUT_ROOT="$(cd "$HARNESS_DIR/.." && pwd)/results"
RUNS=3
COSTS=0
GENERIC=0
USE_VARIANTS=1
CONTAINER=""
LABEL=""
EXTRA_VARS=()
GUCS=()
TAG=""
QUERIES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -c) CONTAINER="$2"; shift 2 ;;
    -s) LABEL="$2"; shift 2 ;;
    -q) QDIR="$(realpath "$2")"; shift 2 ;;
    -o) OUT_ROOT="$(realpath "$2")"; shift 2 ;;
    -r) RUNS="$2"; shift 2 ;;
    --costs) COSTS=1; shift ;;
    --generic) GENERIC=1; shift ;;
    --var) EXTRA_VARS+=("$2"); shift 2 ;;
    --set) GUCS+=("$2"); shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --no-default-variants) USE_VARIANTS=0; shift ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) QUERIES+=("$1"); shift ;;
  esac
done
[[ -n "$CONTAINER" && -n "$LABEL" ]] || { sed -n '2,25p' "$0"; exit 2; }

OUT="$OUT_ROOT/$LABEL"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.tsv"
[[ -f "$SUMMARY" ]] || printf 'query\tvariant\tmode\texec_ms_median\texec_ms_runs\tplan_ms\tshared_hit\tshared_read\ttemp_written\tspill\trows_removed\tworkers\trows_out\tindexes\tplan_shape\n' > "$SUMMARY"

# copy the queries into the container so `\ir _params.sql` resolves
REMOTE="/harness/q-$(basename "$QDIR")-$$"
docker exec "$CONTAINER" mkdir -p "$REMOTE"
docker cp -q "$QDIR/." "$CONTAINER:$REMOTE/"
if [[ ! -f "$QDIR/_params.sql" ]]; then docker cp -q "$HARNESS_DIR/queries/_params.sql" "$CONTAINER:$REMOTE/_params.sql"; fi
trap 'docker exec "$CONTAINER" rm -rf "$REMOTE" >/dev/null 2>&1 || true' EXIT

if [[ ${#QUERIES[@]} -eq 0 ]]; then
  for f in "$QDIR"/*.sql; do b="$(basename "$f" .sql)"; [[ "$b" == _* ]] || QUERIES+=("$b"); done
fi

opts="ANALYZE, BUFFERS, TIMING OFF, SUMMARY ON"
[[ $COSTS -eq 1 ]] && opts="$opts, COSTS ON" || opts="$opts, COSTS OFF"
mode="custom"; [[ $GENERIC -eq 1 ]] && mode="generic"
suffix=""; [[ $GENERIC -eq 1 ]] && suffix="-generic"; [[ $COSTS -eq 1 ]] && suffix="$suffix-costs"
[[ $COSTS -eq 1 ]] && mode="$mode-costs"
[[ -n "$TAG" ]] && { suffix="$suffix-$TAG"; mode="$mode-$TAG"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; docker exec "$CONTAINER" rm -rf "$REMOTE" >/dev/null 2>&1 || true' EXIT

for spec in "${QUERIES[@]}"; do
  q="${spec%%@*}"
  file="$QDIR/$q.sql"
  [[ -f "$file" ]] || { echo "missing $file" >&2; continue; }
  if [[ $GENERIC -eq 1 ]] && ! grep -q '^\\if :generic' "$file"; then
    echo "skip $q (no generic branch)"; continue
  fi
  if [[ "$spec" == *@* ]]; then
    variants=("${spec#*@}")
  elif [[ $USE_VARIANTS -eq 1 ]] && grep -q '^-- variants:' "$file"; then
    read -r -a variants <<< "$(grep -m1 '^-- variants:' "$file" | sed 's/^-- variants://')"
  else
    variants=("")
  fi
  for v in "${variants[@]}"; do
    vlabel="$(echo "$v" | tr -d '=' | tr ',' '-')"
    name="$q${vlabel:+-$vlabel}$suffix"
    script="$TMP/$name.psql"
    {
      echo "\\set ON_ERROR_STOP on"
      for kv in "${EXTRA_VARS[@]}" $(echo "$v" | tr ',' ' '); do echo "\\set ${kv%%=*} ${kv#*=}"; done
      echo "\\set generic $GENERIC"
      for g in "${GUCS[@]}"; do echo "SET ${g%%=*} = '${g#*=}';"; done
      echo "\\set explain 'EXPLAIN ($opts)'"
      for i in $(seq 0 "$RUNS"); do
        echo "\\echo ==RUN $i=="
        echo "\\i $REMOTE/$q.sql"
      done
    } > "$script"
    raw="$TMP/$name.raw"
    start=$(date +%s.%N)
    if ! docker exec -i "$CONTAINER" psql -U postgres -d postgres -X -P pager=off < "$script" > "$raw" 2>&1; then
      echo "ERROR running $name (see $OUT/$name.txt)" >&2
    fi
    line="$(python3 -I "$HARNESS_DIR/parse-explain.py" "$raw" "$OUT/$name.txt" "$q" "${v:-default}" "$mode")"
    echo "$line" >> "$SUMMARY"
    printf '%-60s %10s ms  hit=%s read=%s  (%.0fs)\n' "$name" "$(cut -f4 <<< "$line")" "$(cut -f7 <<< "$line")" "$(cut -f8 <<< "$line")" "$(echo "$(date +%s.%N) - $start" | bc)"
  done
done
