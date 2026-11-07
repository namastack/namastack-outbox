# I-b: index proposal (no measurements)

> **Rebased to 270c42cf (2026-11-06).** Baseline = `schema/mysql/outbox-tables.sql` at 270c42cf. The vocabulary S, S+, R, R+, C, PA below refers to the old baseline's column lists; rebased definitions: S `(status, created_at, id, partition_no, next_retry_at)`, S+ S plus `record_key`, R `(status, next_retry_at, partition_no)`, R+ `(status, next_retry_at, partition_no, created_at, record_key)`, C `(status, partition_no)`, PA `(partition_no, status, next_retry_at, created_at, id, record_key)`, K and Kc unchanged.
> Name map old -> new: `idx_outbox_record_status_completed_created_id` -> `idx_outbox_record_status_created_id` (A), `..._status_completed_retry` -> `..._status_retry_partition` (R), `..._status_completed_partition` -> `..._status_partition` (P/C/M), `..._partition_status_completed_retry` -> `..._partition_status_retry` (PA). `idx_outbox_record_key_completed_created_id` (K) is unchanged. Rule applied: `completed_at` removed from every status-led or partition-led index (the new baseline has none there; `status = NEW/FAILED` implies `completed_at IS NULL`), K and K variants keep it. The `completed_at`-bearing status-led variants of the old sets are E-candidates for round 1 (STATE.md branch change list), not in these DDLs. The body below is the pre-rebase text: index names, column lists and "completed_at IS NULL as ref" arguments in it refer to the old baseline.
> Per set: IB-1 K,S; IB-2 K,S,R; IB-3 K,S,R,C; IB-4 K,S+,R+; IB-5 K,PA; IB-6 K,S,PA; IB-7 Kc,S (Kc `idx_outbox_record_key_created (record_key, created_at)` and its intent unchanged).
> Duplicates: **IB-3 is now identical to IA-4** (C lost `completed_at`, so `(status, partition_no)` equals the new branch P and S has no `record_key`): the old distinction "partition_no before completed_at" no longer exists, so the stated intent of IB-3 (partition ranges exact for counts) is the new baseline's P. IB-1 = IA-1, IB-2 = IA-2. S+ equals the baseline A, so IB-4 differs from the baseline by R+ (wider R) and by dropping P. No IB set equals the new baseline. Sizes/write costs: see IA note.

Inputs: the 74 forms of `Q-merged/FILES.txt`, usage map 1/2/6, scenarios, ADR-0005 index rule, branch/main MySQL DDL. MySQL 8.4, InnoDB.
DDL: `IB-1..IB-7.ddl.sql` (branch `outbox-tables.sql` with only the `outbox_record` index list replaced).

**Index names are reused on purpose.** Hinted forms name `idx_outbox_record_status_completed_created_id` (S),
`..._key_completed_created_id` (K), `..._status_completed_retry` (R) and `..._status_completed_partition` (C). A set keeps those names for the
role the hint expects, so hinted forms stay valid. Sets without the role (IB-5/6/7) must use unhinted forms.

## Index vocabulary

| Id | Key (PK `id` is implicit suffix unless listed) | Typical entry (36-char key/id) | Role |
|---|---|---|---|
| K | `(record_key, completed_at, created_at, id)` | ~88 B | strict/non-strict subquery probe, pages. `completed_at` 2nd: `IS NULL` jumps over a hot key's COMPLETED history (S5). Implicit `id` makes the explicit one free |
| Kc | `(record_key, created_at)` | ~84 B | not touched by `upd_complete`; probe needs a clustered lookup per row for `completed_at` |
| S | `(status, completed_at, created_at, id, partition_no, next_retry_at)` | ~60 B | ordered scan for `ORDER BY created_at, id LIMIT n`; `id` right after `created_at` is required, otherwise partition_no sits between and the sort needs a filesort. partition/due filters are index-condition (no lookup) |
| S+ | S plus `record_key` | ~97 B | keys query covering (no clustered lookup for `o.record_key`) |
| R | `(status, completed_at, next_retry_at, partition_no)` | ~59 B | `ORDER BY next_retry_at LIMIT 1` for oldest_due |
| R+ | R plus `created_at, record_key` | ~100 B | oldest_due_strict gets `created_at`/`record_key` for the probe without lookup |
| C | `(status, partition_no, completed_at)` | ~56 B | narrowest status-led index; partition ranges exact for counts, so no skip scan needed. (Branch has `(status, completed_at, partition_no)`, where partition_no is only a filter.) |
| PA | `(partition_no, status, completed_at, next_retry_at, created_at, id, record_key)` | ~100 B | partition-leading, covering: one exact range per IN value |

Notes that drive the choices:
- `status = NEW` implies `completed_at IS NULL` (invariant), so `completed_at` right after `status` costs only 4 B and gives the `IS NULL` ref.
  For COMPLETED the column is high-cardinality, which makes SKIP_SCAN on `completed_at` useless for COMPLETED counts and only attractive for NEW.
- Descending key parts: every `ORDER BY` is ascending, and InnoDB can scan backwards, so no `DESC` part is proposed.
- Partition-leading and `IN (16/64/256)`: one range per value, so the result is ordered per partition, not globally. `ORDER BY created_at LIMIT n`
  therefore needs a filesort of all matching rows (cheap for S2/S6, ~1M entries for S4/S5). Counts and MIN over ranges are exact, and
  partial ownership reads only the owned partitions (S status-led reads all NEW and filters). At 256 values the range count exceeds
  `eq_range_index_dive_limit` (200), so estimates come from index statistics instead of dives (plan choice may differ from 16/64).
- Status-led keys read NEW rows in `created_at` order and stop at `LIMIT`; with 64/256 partitions owned the scan examines ~256/64 times
  the rows returned (filter by ICP), without lookups.
- `COUNT(*)`/`MIN` on a status-led index with `partition_no` as trailing filter reads every NEW entry (S4: 1M, S7 FAILED: 390k).
- Index merge and invisible indexes are not part of any set (merge does not give ordering; invisible indexes keep write cost).

## Per query: minimal index and expected plan

| Query | Best form(s) | Index | Plan / rows (S2 full, S4, S5, S8 64 of 256) |
|---|---|---|---|
| keys_strict | 2 (row compare; 0 equivalent), 6/7 with hints | outer S/S+; subquery K | outer `ref` (status, NULL), ordered, ICP on partition/due; anti-join probe `ref record_key + completed_at NULL + range created_at` on K, covering. Examined ~10-12 (S2), ~40-50 at S8 (4x), S5: scan until 10 unblocked heads (blocked rows each pay a probe). PA: range per partition on due rows then filesort top-N: S2 1.2k rows, S4 ~1M (bad) |
| keys_nonstrict | 2; 7 with hints | as above; the subquery also filters `status`/`next_retry_at` by clustered lookup of the (few) incomplete rows of the key | same as strict; GROUP BY forms (5/11) read all due NEW + temp table: avoid except with PA on small backlogs |
| pages | 1 (hint K) or 0 | K | `range` with one dive per key, ordered by `record_key, created_at`; `SELECT *` costs a clustered lookup per returned (incomplete) row. Hint guards against S being chosen (ref on 1M NEW rows) at 100 keys |
| oldest_created | 2 (scalar subquery keeps NULL semantics) | S (ordered), PA otherwise | S: `ref`, first row passing partition ICP; ~1 row (S2), 4 at S8. `MIN()` (0) cannot stop early (range on a later key part): full NEW scan. PA: all NEW rows in owned partitions |
| oldest_due_nonstrict | 3 (hint R, scalar `ORDER BY LIMIT 1`) | R | `ref` + ICP, ordered, first passing row. Without R: filter all NEW + top-N sort |
| oldest_due_strict | 7 (R/R+ + K), 6 without hints | R/R+, K | R ordered by due time, probe K per candidate until the first unblocked one; R+ avoids the clustered lookup for `created_at`/`record_key`. Without R every due row is probed (S4: 1M probes) |
| count_partitions | 5 (`completed_at IS NULL`); 6 with C; 0 | S/R scan, or C, or PA | S/R: reads all NEW entries, filter partition in index. C or PA: exact ranges (cost proportional to owned rows; S8 reads 1/4) |
| count_statuses | 0 or 3 (hint C) ; 1 for PA | narrowest status-led index (C > R > S) | covering `ref` scan of NEW + FAILED entries (S7: 390k FAILED); `GROUP BY` forbids skip scan. PA (not status-led): full index scan, or form 1 where skip scan on `partition_no` may apply |

## Sets

Write cost columns = secondary indexes written per operation (a changed key column = delete-mark + insert; unchanged `next_retry_at` rewrite is not an
index write). `retry` changes `next_retry_at`; `fail` changes `status`; `complete` changes `status`, `completed_at`.

| Set | Secondary indexes | insert | complete | retry | fail | delete | Size per row (all indexes, ~36 B keys) |
|---|---|---|---|---|---|---|---|
| IB-1 | K, S | 2 | 2 | 1 | 1 | 2 | ~150 B |
| IB-2 | K, S, R | 3 | 3 | 2 | 2 | 3 | ~210 B |
| IB-3 | K, S, R, C | 4 | 4 | 2 | 3 | 4 | ~265 B |
| IB-4 | K, S+, R+ | 3 | 3 | 2 | 2 | 3 | ~285 B |
| IB-5 | K, PA | 2 | 2 | 1 | 1 | 2 | ~190 B |
| IB-6 | K, S, PA | 3 | 3 | 2 | 2 | 3 | ~250 B |
| IB-7 | Kc, S | 2 | 1 | 1 | 1 | 2 | ~145 B |

Branch for comparison: 4 indexes, insert 4 / complete 4 / retry 2 / fail 3 / delete 4, ~345 B per row (S is wide and carries `record_key` and `id`).
PA rewrites a ~100 B entry for retry/complete/fail; S+/R+ likewise (IB-4 trades write bytes for lookup-free reads).

### IB-1 minimal (K, S)
- Forms: KS 2, KN 2, PG 1, OC 2, DN 2, DS 6 (or 1), CP 5, CS 0.
- P2 slower: oldest_due_* read and sort/filter all due NEW rows (no ordered path); DS probes every due row (S4/S5 expensive). count_statuses reads
  the 60 B S entries. S8 counts read all NEW. P1 is as cheap as this design gets (2 indexes).

### IB-2 minimal + due-time order (K, S, R)
- Forms: KS 2, KN 2, PG 1, OC 2, DN 3, DS 7 (alt 6), CP 5 (R or S), CS 0.
- Cost over IB-1: +1 index on insert/complete/delete, retry and fail also touch R. DS still needs clustered lookups for the probe columns.

### IB-3 + narrow status/partition index (K, S, R, C)
- Forms: as IB-2 but CP 6 (hint C) and CS 3 (hint C). C replaces the branch's `(status, completed_at, partition_no)`.
- Gain: partial-ownership counts at exact ranges, smallest scan for count_statuses. Cost: one more index on every insert/complete/fail/delete.
  Candidate to show whether P2 counts justify a 4th index (ADR rule).

### IB-4 covering (K, S+, R+)
- Forms: KS 2, KN 2, PG 1, OC 2, DN 3, DS 7, CP 5, CS 0.
- No clustered lookup in any status-led query; wider entries on complete/retry/fail. Keys query reads ~97 B entries. Read-optimal for the status-led design.

### IB-5 partition-leading, minimal (K, PA)
- Forms (unhinted): KS 2, KN 2, PG 0, OC 0, DN 0, DS 1, CP 5, CS 1.
- Gains: 2 indexes, one covering index for everything partition-scoped, exact ranges at partial ownership. Risks: keys/oldest_* touch all due/NEW rows of the
  owned partitions (S4, S5 backlog; filesort top-N), count_statuses has no status-led index (full scan of PA; skip scan unlikely), 256-value IN loses
  index dives. Meant to measure the whole partition-leading hypothesis.

### IB-6 hybrid (K, S, PA)
- Forms: KS 6 (hints S, K), KN 7, PG 1, OC 3 (hint S), DN 0, DS 1, CP 5, CS 0. Keys and oldest_created use ordered S; PA serves counts and due-time
  queries via exact partition ranges. 3 indexes; shows whether PA replaces R and C.

### IB-7 cheapest complete (Kc, S)
- Forms: KS 0, KN 2, PG 0, OC 2, DN 2, DS 6, CP 5, CS 0 (unhinted: Kc has another name).
- `upd_complete` touches only S (K no longer contains `completed_at`). Penalty: probes of a hot key read its COMPLETED history through clustered lookups
  (S5: ~3000 per key), pages likewise. Quantifies what `completed_at` in K buys versus costs.

## Schema-change ideas (need a user decision before adoption)

1. Surrogate integer for `record_key` (e.g. `record_key_hash BIGINT` generated column, K on the hash): K entry ~88 B to ~56 B; hash collisions need a
   re-check of `record_key` and the 0900_ai_ci collation semantics differ (case-insensitive today). Generated/virtual column.
2. `status` as `TINYINT` (smaller prefix in all status-led entries, ~6 B saved per entry); column type change (ADR-0005 non-goal).
3. Functional/virtual index emulating a partial index (`CASE WHEN completed_at IS NULL THEN created_at END`): NULLs are still indexed, so
   COMPLETED rows keep an entry and the write saving is only on later updates; not proposed.
4. `id` shorter than `VARCHAR(255)` content (e.g. `BINARY(16)`): the PK suffix is in every entry (~37 B); column type change.

## MariaDB

All DDL is plain composite indexes (no DESC, no functional index, no invisible), valid on MariaDB through `schema/mysql`. The forms with
hints (`SKIP_SCAN`, `JOIN_ORDER`, `NO_BNL`, `INDEX(alias idx)`) are MySQL syntax. MariaDB ignores or rejects some of them, and MariaDB has no skip scan (IB-3
and IB-5 therefore do not depend on one). Key size is under 3072 B for all indexes (S+ ~2.2 KB maximum).
