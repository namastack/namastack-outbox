#!/usr/bin/env bash
# All G1 equivalence checks for one hypothesis on one container. Usage: eq-suite.sh <container> <H0x> [extra k=v]
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; Q="$D/qdirs"; H="$2"; C="$1"; shift 2
E="$D/eq-check.sh"
case $H in
  H01|H02) files="findRecordKeysInPartitions_filter findOldestDueNextRetryAtByPartitions_filter" ;;
  *) files="findRecordKeysInPartitions_filter findRecordKeysInPartitions_nofilter findOldestDueNextRetryAtByPartitions_filter" ;;
esac
for p in 64 256; do for f in $files; do
  "$E" "$C" "$Q/base/$f.sql" "$Q/$H/$f.sql" parts=$p "$@" || true
  if [[ $H == H04 && $f == findRecordKeys* ]]; then
    for n in 10 100; do "$E" "$C" "$Q/base/$f.sql" "$Q/$H/$f.sql" --head $n parts=$p "$@" || true; done
  fi
done; done
