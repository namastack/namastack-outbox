# `partition_no` as an index key column

Verdict: **rejected, `INCLUDE` stays**. Sources: `R3-screen.md`, `R3X-screen-extension.md`.

| Form | S8-S4 `keys_strict` or `oldest_due_strict` vs set A,B1,C,D2 |
|---|---|
| A `(partition_no, status, created_at, id)` | `keys_strict` 704 vs 0.90 ms; S16-S4 215 vs 0.83 |
| A `(status, partition_no, created_at, id)` + C `(status, partition_no, next_retry_at)` | `keys_strict` 695, `oldest_due_strict` 657 vs 0.67 |
| C `(partition_no, status, next_retry_at)` | `oldest_due_strict` 198; S16-S4 114 |
| D `(partition_no, status)` | `count_statuses` 107 vs 27.7, no `count_partitions` gain |

Cause: a global `ORDER BY created_at, id` over several partitions loses index order, so the plan reads and sorts every eligible row (250 815 rows, 750 501 buffers). Not tested: an instance owning a single partition. A per-partition `LATERAL` form could restore order and is in round 4 (E08).
