#!/usr/bin/env bash
# G1 iteration 2 equivalence checks for one query dir on one container.
# Usage: eq-suite.sh <container> <qdir-name> [extra k=v]   (uses ../G1-iter1/eq-check.sh, tie analysis)
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; Q="$D/qdirs"; C="$1"; H="$2"; shift 2
E="$D/../G1-iter1/eq-check.sh"
pairs="findRecordKeysInPartitions_filter:findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter:findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter:findOldestDueNextRetryAtByPartitions_filter"
for v in fence scalar implied; do
  [[ -f "$Q/$H/findRecordKeysInPartitions_nofilter_$v.sql" ]] && pairs="$pairs findRecordKeysInPartitions_nofilter:findRecordKeysInPartitions_nofilter_$v"
done
for p in 64 256; do for pr in $pairs; do
  o=${pr%%:*}; r=${pr#*:}
  "$E" "$C" "$Q/base/$o.sql" "$Q/$H/$r.sql" parts=$p "$@" || true
  if [[ $r == findRecordKeys* ]]; then "$E" "$C" "$Q/base/$o.sql" "$Q/$H/$r.sql" --head 10 parts=$p "$@" || true; fi
done; done

# H09 non-partial: the unrewritten (no LIMIT, GROUP BY) filter query with swapped predicate
if [[ -f "$Q/$H/findRecordKeysInPartitions_filter_orig.sql" ]]; then for p in 64 256; do
  "$E" "$C" "$Q/base/findRecordKeysInPartitions_filter.sql" "$Q/$H/findRecordKeysInPartitions_filter_orig.sql" batch=1 parts=$p "$@" || true; done; fi
