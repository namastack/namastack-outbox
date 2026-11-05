# Index proposal, run B (MariaDB 12.3.3, `outbox_record`)

All six DDLs (`B1..B6.ddl.sql`) were applied on a throw-away `mariadb:12` (12.3.3): valid. Worst-case key length of the widest index
is about 2.2 KB (utf8mb4, two VARCHAR(255) = 2040 bytes + ints/status) < 3072. Only `outbox_record` indexes differ from the branch DDL.

## Index catalogue

| Key | Name | Columns | Typical entry (36-char id/key) |
|---|---|---|---|
| I1 | `idx_outbox_record_status_completed_created_id` (branch name) | `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)` | ~100 B |
| I2 | `idx_outbox_record_key_completed_created_id` (branch name) | `(record_key, completed_at, created_at, id)` | ~85 B |
| I3 | `idx_outbox_record_status_completed_retry` (branch name) | `(status, completed_at, next_retry_at, partition_no)` | ~60 B (+ id) |
| I4 | `idx_outbox_record_status_completed_partition` (branch name) | `(status, completed_at, partition_no)` | ~55 B (+ id) |
| IP | `idx_outbox_record_partition_status_completed_retry_created_id` (new) | `(partition_no, status, completed_at, next_retry_at, created_at, id, record_key)` | ~100 B |

## Per-index reasoning (partial ownership first)

- **Ordered scan, status-leading (I1).** Equality on `status`, `completed_at IS NULL` is a single range; inside it entries are in
  `(created_at, id)` order, so `ORDER BY created_at, id LIMIT n` stops early (no filesort). `partition_no`, `next_retry_at` and
  `record_key` are in the index, so the partition and due filters and the correlated `NOT EXISTS` key are evaluated on the covering
  index with no clustered lookups. Cost under partial ownership: the scan visits every owner's rows, so rows examined are about
  `n * 256/owned / pass-rate` (64 of 256: 4x, 16 of 256: 16x), plus blocked keys. It is cheap when the backlog is large (S4: stops
  after ~40-160 entries) and never needs a sort. It degrades when the NEW rows near the front of `created_at` belong to other
  instances or are not due (S6 with 195k future retries: the scan must skip them, because `next_retry_at` is not a prefix part).
- **Multi-range seek, partition-leading (IP).** `partition_no IN (64 values)` x `status=` x `completed_at IS NULL` x
  `next_retry_at <= now` becomes 64 (or 16) tight ranges that read only due, incomplete, owned rows (S6: 5000 due instead of 195k NEW; S8
  reads 1/4 of the due rows). The price: ranges are ordered per partition, not globally, so `ORDER BY created_at, id` becomes a filesort
  over all due owned rows, and the `NOT EXISTS` is evaluated per candidate before the `LIMIT` applies (S4: ~250k due rows at 64
  partitions, S5: 200k). This is the trade-off: IP wins when the due set is small relative to the NEW/future set (S2, S6, S7), loses by
  orders of magnitude on deep due backlogs (S4, S5). `next_retry_at` sits before `created_at` so the due range prunes; `created_at, id,
  record_key` after it make the index covering. With 256 values in the `IN` list (full ownership) `eq_range_index_dive_limit`=200 is
  exceeded and MariaDB estimates from index statistics instead of dives, so the range-vs-scan choice becomes statistics-driven; with 64 or
  16 values the dives are exact. No skip scan exists in MariaDB, so `partition_no` after the ordering columns can only filter, not seek.
- **Key-leading (I2).** `record_key IN (...)`, `completed_at IS NULL`, ordered by `(created_at, id)`: serves pages
  (`ORDER BY record_key, created_at, id` is satisfied across the IN ranges, no filesort) and every predecessor `NOT EXISTS` probe
  (equality + `IS NULL` + range on `created_at`, `id` covering the tie-break). Required by all sets. `status` is filtered on the
  clustered row (non-covering lookup per row, but the NULL range holds only NEW/FAILED rows of the key; pages stay small). Risk:
  the optimizer may prefer an I1/I3/I4 `status` ref with `rowid_filter`/`index_merge` for pages; `pa-05` (`SET STATEMENT`) or `pa-04`
  (IGNORE the others) neutralise that.
- **Count/oldest queries are Priority 2 and rare (6 per 15 s per instance vs 0.5 key query/s).** By the index rule they should
  piggy-back on the P1 indexes unless they pay for themselves.

## Sets

Maintained-index counts: `ins` = secondary inserts, `complete` = touched on NEW->COMPLETED (status, completed_at change), `retry` = touched
on `next_retry_at` change, `fail` = touched on NEW->FAILED (status change), `del` = delete-marks. I2 is touched only by
`completed_at` changes (insert, complete, delete). IP is touched by every operation that changes status, completed_at or next_retry_at.

| Set | Indexes | ins | complete | retry | fail | del | Secondary bytes per row (36-char ids) |
|---|---|---|---|---|---|---|---|
| B1 | I1, I2 | 2 | 2 | 1 (I1) | 1 (I1) | 2 | ~185 B |
| B2 | I1, I2, I3 | 3 | 3 | 2 | 2 | 3 | ~280 B (+id ~37 B) |
| B3 | I1, I2, I4 | 3 | 3 | 1 | 2 | 3 | ~275 B |
| B4 | I2, IP | 2 | 2 | 1 (IP) | 1 (IP) | 2 | ~185 B |
| B5 | I1, I2, IP | 3 | 3 | 2 | 2 | 3 | ~285 B |
| B6 | I1, I2, I3, I4 (= branch DDL) | 4 | 4 | 2 | 3 | 4 | ~380 B |

Write-cost ranking (cheapest first): B1 = B4 < B2 = B3 = B5 < B6. Relative to `main` (5 narrow indexes: insert 5, complete 4, retry 2,
fail 3, delete 5): B1/B4 cost 40-60 % of main's index writes; B6 (branch) is 4/4/2/3/4 but with wide entries.
Note that `upd_complete` also moves the entry from the small NULL range into the growing COMPLETED part of I1/I2/IP/I3/I4, so
the incomplete range stays compact and purge of delete-marked entries is the S9 cost.

### B1 minimal: I1 + I2 (P2 served by P1 indexes only)
- keys_strict: `ks-01-split` (`ks-08-force-outer`/`ks-09-force-both` as hinted alternates); keys_nonstrict: `kn-01-split`.
  Plan: outer `index` ordered on I1 (range `status=NEW, completed_at IS NULL`, Using index, no filesort), subqueries `ref/range` on I2.
  Rows examined at S8-S2 about 4x the returned rows plus blocked keys; S4 ~40; S5 dominated by the 40 % blocked keys (up to 20 heads
  blocking 2000 records each) and subquery probes.
- pages: `pa-00-base` on I2 (fallback `pa-05-setstmt-nomerge` or `pa-04`, but with only I1 and I2 there is no competing status
  index except I1, which an IN-key query cannot use anyway).
- count_statuses: `cs-00-base` (or `cs-06-incomplete` if default metrics): covering range scan of I1 on `status` prefix; cost grows with the
  COMPLETED history only if COMPLETED is requested.
- count_partitions: `cp-00-base`/`cp-04-incomplete`: I1 covering scan of all NEW, `partition_no` filtered on the index (O(NEW backlog),
  S4 1M entries).
- oldest_created: `oc-00-base` (ordered I1 scan, first owned row ends it; or `oc-01-min`).
- oldest_due_nonstrict: `odn-01-order` on I1 (no `next_retry_at` prefix, scans NEW incomplete rows until a due, owned row is found;
  `ORDER BY next_retry_at` forces a filesort of due rows, so O(due)). oldest_due_strict: `ods-02-split` (same outer as keys, sorted by
  `next_retry_at`: filesort over all eligible rows, O(backlog)).
- P2 that get slower vs branch: oldest_due_* (no I3), count_partitions (wider covering scan than I4, no pruning).

### B2: B1 + I3 (ordered `next_retry_at` for the oldest-due monitors)
- oldest_due_nonstrict: `odn-03-order-force`/`odn-01-order` on I3, range `next_retry_at <= now`, ordered, covering, `partition_no` filtered
  on the index, stops at the first owned row (cheap). oldest_due_strict is still dominated by the NOT EXISTS (`ods-02-split`); I3 only
  helps if the optimizer drives from it, which the strict form rarely wants.
- Everything else as B1. Adds retry/fail/complete/insert maintenance for a 6/15 s query: marginal by the index rule, included to
  measure it.

### B3: B1 + I4 (narrow covering index for counts)
- count_statuses `cs-01-use-idx4` or `cs-06`/`cs-07-incomplete-force`; count_partitions `cp-01-use-idx4`/`cp-05-incomplete-force`
  (`status, completed_at=NULL, partition_no IN` multi-range: reads only owned NEW entries, ~55 B entries instead of ~100 B).
  Stays status-conditional: forms 04-08 assume `completed_at IS NULL` statuses only (NEW/FAILED).
- Slower P2 vs branch: oldest_due_*.

### B4: partition-leading, no status-leading index: I2 + IP
- keys_strict `ks-01-split`, keys_nonstrict `kn-01-split` without hints: outer is `range` on IP (64/16 multi-range, covering,
  `Using filesort`). Rows examined = due owned rows exactly (S8-S2 about 250, S8-S6 about 1250, S8-S7 about 2500) but S4/S5 = whole
  due backlog before LIMIT. Expect big win on S6/S7/S2, big loss on S4/S5.
- pages `pa-00-base` on I2. count_partitions `cp-00-base`/`cp-04` on IP (multi-range, exact rows, covering). count_statuses: no
  `status`-prefix index; `cs-00-base` uses a full covering index scan of IP (partition_no unrestricted), `cs-04-values-subquery` needs
  code and still cannot seek. This set is the one with slow `count_statuses` and `oldest_created`
  (`oc-01-min`: reads all NEW owned entries, no ordering by created_at). oldest_due_nonstrict `odn-01-order`: IP ranges, per-partition
  ordered on `next_retry_at`, filesort over due rows.
- Cheapest write set tied with B1; the `status, completed_at` prefix inside IP is touched by every transition.

### B5: hybrid I1 + I2 + IP
- Optimizer chooses between ordered scan (I1) and multi-range+filesort (IP) from statistics; correctness risk is plan flapping, so
  measure with hints in both directions (`ks-08-force-outer` forces I1; an added `FORCE INDEX (idx_outbox_record_partition_status_completed_retry_created_id)`
  would force IP). counts/oldest as in B1 (I1) but count_partitions/oldest_due can use IP ranges. Three indexes, widest.

### B6: read-optimal = the branch DDL (I1, I2, I3, I4)
- Every P2 query has a dedicated index: keys as B1, oldest_due_nonstrict on I3, counts on I4. Highest write cost: insert 4,
  complete 4, retry 2, fail 3, delete 4. Included as upper bound and for P2-vs-P1 attribution.

## Ideas that need more than indexes (not in any set; need a user decision)
- Generated/virtual column `is_open` (or a persistent boolean) so one equality column replaces `status + completed_at IS NULL` combinations,
  or a stored `NEW`-only shadow column (`pending_created_at` NULL when COMPLETED) giving a sparse index (NULLs are indexed in InnoDB, so it would not be
  sparse without a different encoding): allowed to test, but column additions go beyond ADR-0005's "no column type changes" and
  need a user decision.
- Shrinking `id` to a fixed-width type (BINARY(16)) would cut every secondary entry by ~20 B: a column type change, out of scope.

## Output
`/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/I-B/{proposal.md,B1..B6.ddl.sql}`
