#!/usr/bin/env bash
# G4/G5 iteration 1 query dirs. Run first.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; O="$D/qdirs"; rm -rf "$O"
mk() { mkdir -p "$O/$1"; cp "$DB/harness/queries/_params.sql" "$O/$1/"; }
mk orig; for q in countByStatus countRecordsByPartitions countRecordsByPartition findOldestCreatedAtByPartitions findFailedRecordsBefore deleteFailedRecords_selectIds; do cp "$DB/harness/queries/$q.sql" "$O/orig/"; done
mk h32; cp "$DB/experiments/H32/queries/"*.sql "$O/h32/"
mk h31; cp "$DB/experiments/H31/queries/findOldestCreatedAtByPartitions_noowned.sql" "$O/h31/"
mk h40; cp "$DB/experiments/H40/queries/"*.sql "$O/h40/"
mk h41; cp "$DB/experiments/H41/queries/"*.sql "$O/h41/"
mk h42; cp "$DB/experiments/H42/queries/"*.sql "$O/h42/"
mk h30; cp "$DB/experiments/H30/queries/countByStatus_grouped.sql" "$O/h30/"
ls "$O"/*
