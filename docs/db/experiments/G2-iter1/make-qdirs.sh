#!/usr/bin/env bash
# Builds merged query dirs for G2 iteration 1: harness/queries overlaid with each H's files.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"
OUT="$D/qdirs"; rm -rf "$OUT"
for h in base H11 H12 H13 H13c; do
  mkdir -p "$OUT/$h"; cp "$DB/harness/queries/"*.sql "$OUT/$h/"
  case $h in
    H11|H12|H13) cp "$DB/experiments/$h/queries/"*.sql "$OUT/$h/" ;;
    H13c) cp "$DB/experiments/H13/queries-h11/"*.sql "$OUT/$h/" ;;
  esac
done
touch "$OUT/H13/.pages-only" "$OUT/H13c/.pages-only"
echo "$OUT"
