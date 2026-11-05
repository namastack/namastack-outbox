# keys_strict: proposed forms (independent proposer)

## ne-split
- **Idea:** Split the predecessor test into two NOT EXISTS: strictly older created_at, and same created_at with smaller id. Drops the OR.
- **Why it may be faster:** Each subquery is a pure ref+range probe on (record_key, completed_at IS NULL, created_at) / (.., created_at = x, id < y), with no OR to defeat range building; the first probe can be answered from the index alone and stops at the first match. Second probe almost always finds nothing (ties within one second in one key are rare) but is a single-point probe.
- **Access path needed:** idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id) for both probes; outer idx_outbox_record_status_completed_created_id ordered scan (status, completed_at IS NULL, created_at, id) with partition_no/next_retry_at as index-condition filter.
- **Semantic risks:** Same rows and order as base. Two probes per outer row instead of one, so slower if the OR was already handled by one range. Planner could pick a different outer index.
- **MySQL portability:** Valid MySQL 8.

## ne-tuple
- **Idea:** Row-constructor comparison (earlier.created_at, earlier.id) < (o.created_at, o.id) replaces the OR expansion.
- **Why it may be faster:** A row comparison is the natural expression of the (created_at, id) order and can be a single range scan on the composite (created_at, id) key part in engines that support it.
- **Access path needed:** idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id); outer idx_outbox_record_status_completed_created_id.
- **Semantic risks:** MariaDB does not turn a row `<` into a range on the two key parts (it is only used as a filter / range on the first part); possibly worse than base. Correctness identical (lexicographic).
- **MySQL portability:** Valid MySQL 8 (MySQL 8 does range-optimize row comparisons).

## lj-antijoin
- **Idea:** LEFT JOIN ... IS NULL anti-join instead of NOT EXISTS.
- **Why it may be faster:** MariaDB applies the 'Not exists' join optimization (stop at the first match per outer row) and the join optimizer can reorder tables, unlike a correlated subquery which is always executed per outer row after the outer filter.
- **Access path needed:** Outer idx_outbox_record_status_completed_created_id; inner idx_outbox_record_key_completed_created_id with ref (record_key, completed_at=NULL) + created_at range; earlier.id is in the index, so inner is index-only.
- **Semantic risks:** Same rows (earlier.id is the PK, NOT NULL, so IS NULL means no match). A join order starting from `earlier` would defeat ORDER BY ... LIMIT early stop; see lj-straight.
- **MySQL portability:** Valid MySQL 8 (becomes an anti-join).

## lj-straight
- **Idea:** Same as lj-antijoin with STRAIGHT_JOIN to pin `o` as the driving (ordered, early-stopping) table.
- **Why it may be faster:** Guarantees the LIMIT-friendly plan (ordered index scan of o on the incomplete prefix, probe, stop after :limit survivors) even if statistics make the optimizer prefer to start from `earlier`.
- **Access path needed:** Same as lj-antijoin.
- **Semantic risks:** STRAIGHT_JOIN is a hint, not a schema dependency; it cannot fail on a different schema. Low risk, can only cost a plan.
- **MySQL portability:** Valid MySQL (STRAIGHT_JOIN).

## win-head
- **Idea:** Rank all incomplete records per key with ROW_NUMBER() and keep the head; then apply status/partition/due filters to the head only. A NEW due record is eligible iff it is the head of its key's incomplete records (any status).
- **Why it may be faster:** One pass over the incomplete set instead of one probe per candidate. Wins when the incomplete set is small relative to candidate probes (e.g. S2 ~14k incomplete rows) and loses when it is large (S4: 1M NEW).
- **Access path needed:** idx_outbox_record_key_completed_created_id gives record_key order, but `completed_at IS NULL` is not the prefix, so the optimizer may scan all of the index (record_key, completed_at) or use idx_outbox_record_status_completed_created_id with (status) unconstrained. Needs a materialized derived table + window sort; no LIMIT early stop. Essentially a full scan of incomplete rows.
- **Semantic risks:** Same rows/order. The derived table materializes all incomplete rows before the filter (conditions on non-partition columns cannot be pushed into the window). Probably regresses at S4/S5; a 'known-bad' control for the large backlog.
- **MySQL portability:** Valid MySQL 8 (window functions).

## ne-split-force-idx1
- **Idea:** ne-split with FORCE INDEX on the outer table to pin the ordered (created_at, id) scan.
- **Why it may be faster:** Prevents the optimizer from choosing a different index and sorting; early stop after :limit survivors.
- **Access path needed:** idx_outbox_record_status_completed_created_id.
- **Semantic risks:** HIGH RISK: FORCE INDEX names idx_outbox_record_status_completed_created_id; the statement fails (error 1176) on a schema without that index (e.g. main's DDL). Only valid on the branch schema.
- **MySQL portability:** FORCE INDEX is valid MySQL syntax; the index must exist.
