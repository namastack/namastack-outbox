# Index proposal, run A (no measurements)

Table facts used: PK `id` is clustered; every secondary entry carries `id` unless already in the key. Realistic entry sizes
(36-char key/id, TIMESTAMP 4 B, INT 4 B, status ~3-9 B): wide idx1 ~100 B, idx2 ~85 B, narrow status index ~50 B.
All DDL pass on `mariadb:12` (worst-case key 2.1 KB < 3072 B). `outbox_instance`/`outbox_partition` unchanged.

## Per-form minimal index (summary)

| Query | Best form | Index and plan |
|---|---|---|
| keys_strict outer | ks-00-base / ks-01-split | `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`: `ref_or_null` on `status=,completed_at IS NULL`, index order = `ORDER BY created_at,id`, no filesort, covering; partition/due filtered in-index. Rows examined = incomplete NEW rows in created order until LIMIT filled (about 4x returned at 64/256, worse when many not-due NEW as in S6). |
| keys_strict inner | same | `(record_key, completed_at, created_at, id)`: `ref` on key, range on `completed_at IS NULL` + created_at, covering, early exit (first match). |
| keys_nonstrict | kn-00/kn-01, or kn-08/09 group-by | same two indexes; group-by variant needs temp table, cheap when due set is small. |
| pages | pa-00-base (pa-03 forced) | key index: `range` on `record_key IN(...)`, `completed_at IS NULL`, ordered by (record_key, created_at, id) with no sort; `status` check needs the clustered row (`SELECT *`), so one PK probe per row regardless. |
| count_statuses | cs-06 | any `status`-led index with `completed_at` second: `ref_or_null` range on `completed_at IS NULL`, covering, skips COMPLETED. Cheapest with a narrow `(status, completed_at, ...)`. |
| count_partitions | cp-04 | covering scan of incomplete entries, `partition_no` must be in the index (idx1, idx3, idx4) or costs a PK probe per row. |
| oldest_created | oc-00/oc-01 | idx1 ordered by created_at with `partition_no` filter in-index, LIMIT stops early. |
| oldest_due_nonstrict | odn-00 | needs `next_retry_at` first after `completed_at`: first owned entry = MIN, near O(1) (loose range). With only idx1 it is a covering scan of all incomplete entries. |
| oldest_due_strict | ods-01/ods-08 | outer as above plus inner key index; ordered by next_retry_at needs idx3 or the due-first index (then PK probe for created_at/record_key unless covered). |

## Sets (index sets for `outbox_record`)

Names: `i1`=`idx_outbox_record_status_completed_created_id`, `i2`=`idx_outbox_record_key_completed_created_id`,
`i3`=`idx_outbox_record_status_completed_retry`, `i4`=`idx_outbox_record_status_completed_partition` (all branch DDL, exact
names kept). `d1`=`idx_outbox_record_status_completed_retry_created (status, completed_at, next_retry_at, partition_no,
created_at, id, record_key)` ("due-first", new). `n1`=`idx_outbox_record_status_completed_created (status, completed_at, created_at)`,
`n2`=`idx_outbox_record_key_completed_created (record_key, completed_at, created_at)` (no explicit id).

| Set | Indexes | Write cost: indexes touched per insert / complete / retry / fail / delete |
|---|---|---|
| A1 minimal | i1, i2 | 2 / 2 / 1 (i1) / 1 (i1) / 2 |
| A2 minimal, no explicit id | n1, n2 | 2 / 2 / 0 / 1 (n1) / 2 ; smallest entries |
| A3 | i1, i2, i3 | 3 / 3 / 2 (i1,i3) / 2 / 3 |
| A4 minimal, due-first | d1, i2 | 2 / 2 / 1 (d1) / 1 / 2 |
| A5 | i1, i2, d1 | 3 / 3 / 2 / 2 / 3 |
| A6 read-optimal (= branch DDL) | i1, i2, i3, i4 | 4 / 4 / 2 / 3 / 4 |

Clustered row rewrite is identical for all sets; the numbers above are secondary-index work only. Entry size: i1 ~100 B (widest,
touched on retry/complete/fail), d1 same width.

### Forms per P1/P2 query (hint forms valid only if the named index exists in the set)

- **A1**: keys_strict ks-00-base (or ks-01-split / ks-08, ks-09 forced); keys_nonstrict kn-00-base / kn-01-split; pages pa-00-base
  (pa-03 force; pa-04 IGNORE names i3/i4 do not exist: unusable, use pa-05); count_statuses cs-06; count_partitions cp-04;
  oldest_created oc-00-base; oldest_due_nonstrict odn-00-base (scan of incomplete entries, O(incomplete)); oldest_due_strict
  ods-01-min. Dropping i3/i4 slows: P2 oldest_due and counts (wider index scan), P3 retention (`next_retry_at` lookups).
- **A2**: same SQL forms without hints (hint names missing: ks-08/09, pa-03/04, oc-04/05, odn-03/04, cp-01/03, cs-01 not usable).
  Dropping explicit `id`: n2 keeps `id` implicitly right after `created_at`, so `ORDER BY record_key, created_at, id`, the
  `(created_at,id)` tie-break comparison and covering are unchanged (this part is free and saves nothing in size because `id` is
  appended anyway; only avoids a duplicated declaration). n1 without `partition_no, next_retry_at, record_key`: ordering
  `created_at,id` still index-served (implicit id), index ~50 B, **but not covering**: keys_strict outer, oldest_created and
  count_partitions need one PK probe per scanned incomplete row; cheap for LIMIT-bounded scans (S4/S2), costly for count_partitions
  on S7 (390k FAILED) and sparse-ownership scans. Adding trailing columns after `created_at` in an id-less wide index would break
  `ORDER BY created_at,id` (filesort of all matches), which is why i1 keeps `id` explicit. Gain: retry updates touch no secondary index.
- **A3**: as A1, odn-00/odn-01 via i3 (hint odn-03/04 valid), counts may pick i3 as covering narrower scan; ods ordered by
  next_retry_at but needs PK probes for created_at/record_key.
- **A4**: keys via d1 range (`next_retry_at <= now`) plus top-N filesort over due rows only (good when NEW future is large, S6;
  bad for S4 backlog of 1M due rows); counts, oldest_created (MIN scan, no order), oldest_due (first owned entry) from d1. ks-00/kn-00
  with d1; pages/inner via i2. Weakest on keys_strict at S4.
- **A5**: both access paths for keys; optimizer or `FORCE INDEX` chooses; hint names i1/i2 present; odn via d1. Costs one more wide index.
- **A6**: all branch hint forms valid (ks-08/09, pa-03/04, cp-01/03, cs-01, oc-04/05, odn-03/04); the reference for read-optimum.

## Schema change beyond indexes

None needed. (A generated `completed_at IS NULL` flag column would allow a narrow index on incomplete rows only; not proposed:
needs a user decision.)

## Notes

- `IS NULL` on `completed_at` as the second key part is a `ref_or_null` prefix, so COMPLETED history is skipped by all indexes.
- With 256-partition `IN` lists (more than `eq_range_index_dive_limit` = 200) range estimates fall back to index statistics: prefer
  forms that do not depend on `partition_no` as the range column (all sets keep `partition_no` as a filter after `status/completed_at`).
- `rowid_filter`/index merge on `pages`: with i1 and i2 both matching, `index_merge` may be picked; pa-05 turns it off.
