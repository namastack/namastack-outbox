#!/usr/bin/env bash
# Builds merged query dirs for G1 iteration 1: harness/queries overlaid with each H's files.
# Every file gets a worst-case hook (`-v partlist_override=999`, custom mode only) after `\ir _params.sql`.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"
OUT="${1:-$D/qdirs}"
rm -rf "$OUT"; mkdir -p "$OUT"
for h in base H01 H02 H03 H04; do
  mkdir -p "$OUT/$h"; cp "$DB/harness/queries/"*.sql "$OUT/$h/"
  [[ $h == base ]] || cp "$DB/experiments/$h/queries/"*.sql "$OUT/$h/"
  for f in "$OUT/$h"/[!_]*.sql; do
    grep -q partlist_override "$f" && continue
    sed -i 's#^\\ir _params.sql$#\\ir _params.sql\n\\if :{?partlist_override}\n\\if :generic\n\\else\n\\set partlist :partlist_override\n\\endif\n\\endif#' "$f"
  done
done
echo "$OUT"
