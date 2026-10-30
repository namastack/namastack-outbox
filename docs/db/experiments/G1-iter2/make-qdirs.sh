#!/usr/bin/env bash
# G1 iteration 2: merged query dirs (harness/queries overlaid with each H's files) + partlist_override hook.
#   base H04 H05 H06 H07 H08 H09a H09b  (H05/H06 also contain findRecordKeysInPartitions_nofilter_fence.sql)
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"
OUT="${1:-$D/qdirs}"
rm -rf "$OUT"; mkdir -p "$OUT"
for h in base H04 H05 H06 H07 H08 H09a H09b H09na H09nb; do
  mkdir -p "$OUT/$h"; cp "$DB/harness/queries/"*.sql "$OUT/$h/"
  src="$DB/experiments/$h/queries"; [[ $h == H09a ]] && src="$DB/experiments/H09/queries-a"; [[ $h == H09b ]] && src="$DB/experiments/H09/queries-b"; [[ $h == H09na ]] && src="$DB/experiments/H09/queries-na"; [[ $h == H09nb ]] && src="$DB/experiments/H09/queries-nb"
  [[ $h == base ]] || cp "$src/"*.sql "$OUT/$h/"
  for f in "$OUT/$h"/[!_]*.sql; do
    grep -q partlist_override "$f" && continue
    sed -i 's#^\\ir _params.sql$#\\ir _params.sql\n\\if :{?partlist_override}\n\\if :generic\n\\else\n\\set partlist :partlist_override\n\\endif\n\\endif#' "$f"
  done
done
echo "$OUT"
