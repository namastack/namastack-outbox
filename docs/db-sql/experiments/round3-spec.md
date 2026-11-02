# Phase 5 — round 3 spec (PostgreSQL) — user's P1/P2 index set (draft)

Source: user ideas 2026-11-02. Prepared while round 2 runs. **No harness source edits until the round 2 driver has ended**
(`bin/bench` rebuilds on newer sources). Finalise after the round 2 reports (reference, S9/S10 variants). Mechanics as in rounds 1–2,
label suffix `-R3`, scenarios S2, S8-S2, S4, S8-S4, S5, S6, S7. No code change.

## Ownership (user decision 2026-11-02)

Partial partition ownership is the default deployment. Primary measurements use `--owned mod4` (labels `S8-S2`, `S8-S4`, plus new `S8-S5`, `S8-S6`, `S8-S7`);
add a 1/16 ownership label on S2 and S4 (`S16-*`, explicit partition list) to see scaling with instance count; full ownership (`S2`, `S4`) is secondary.
Staged run: screen (P1 and P2 only, `--groups HOT,MON`) on S8-S2, S8-S4, S8-S7 + write cost on S2; confirm survivors on all labels with equivalence, S9, S10;
validate the top 2–3 at least twice, order swapped, container restarted, against v1b. Keep a variant at the screen if its plan changes on a P1/P2 query.

## Query set U (all variants unless noted)

v1 catalog, overridden by:
- **U1 `pages`** without `ORDER BY`: `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL`.
  The repository contract keeps pages ordered by creation time and record id (`OutboxRecordRepository.findIncompleteRecordPages` KDoc), so the
  sort moves to the service: after `groupBy { recordKey }`, each page is sorted by `(createdAt, id)`.
- **U2 `oldest_due_strict` and `oldest_due_nonstrict`**: `+ AND o.completed_at IS NULL` on the outer query (neither has it in v1; without it they cannot use a partial index for `o`).
- `keys_*`, `oldest_created`, `count_statuses`, `count_partitions` unchanged. `count_partitions` is the `IN (:partitions)`
  form used by the observability collector (user confirmed 2026-11-02); `count_partition_single` is not a target.

Harness (after round 2): option `--pages-client-sort` that performs group + sort of the fetched rows in the JVM inside the timed section, so
`pages` compares DB sort against DB fetch + memory sort at the same point. Also report DB-only time. Sort key: `created_at`, then `id` with
`String.compareTo`. Check: DB collation vs `compareTo` on the `id` tie-break, which must match the predecessor check `earlier.id < o.id`
(lower-case UUIDs agree; arbitrary ids may not, note it as a risk).

## Indexes

- **A**: `(status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL` (= E02b)
- **B1**: `(record_key, created_at, id) WHERE completed_at IS NULL` (= E04)
- **B2**: `(record_key, created_at, id) INCLUDE (status, next_retry_at) WHERE completed_at IS NULL`
- **C**: `(status, next_retry_at) INCLUDE (partition_no) WHERE completed_at IS NULL`
- **C2**: `(status, next_retry_at) INCLUDE (partition_no, record_key, created_at, id) WHERE completed_at IS NULL` (makes the outer side of `oldest_due_strict` index-only: the `NOT EXISTS` needs `record_key`, `created_at`, `id`)
- **D1**: `(status)`; **D2**: `(status, partition_no)` (both non-partial: `count_statuses` can include COMPLETED via `record-statuses`)

## Variants (PK always present; statistics object kept unless noted)

| Variant | Indexes | SQL | Question |
|---|---|---|---|
| `v1b`, `MIN` | from round 2 | round 2 | references, re-measured on these snapshots |
| `U0` | v1b DDL | U | U1 + U2 alone, no DDL change |
| `UAB1` | A, B1 | U | floor: how bad are the metrics without C/D? |
| `UAB2` | A, B2 | U | B2 INCLUDE: `keys_nonstrict` predecessor index-only? worth the size? |
| `UABC` | A, B1, C | U | C for `oldest_due_*` |
| `UAB2C` | A, B2, C | U | |
| `UABC2` | A, B1, C2 | U | C2 vs C: does index-only `oldest_due_strict` pay for the larger index and its write cost? `oldest_due_nonstrict` needs only C |
| `UABC2D2` | A, B1, C2, D2 | U | C2 in the full proposal, compared with `UABCD2` |
| `UABD1` / `UABD2` | A, B1, D1 / D2 | U | D without C |
| `UABCD1` / `UABCD2` | A, B1, C, D1 / D2 | U | full proposal |
| `UABC-L` | A, B1, C | U + literal `completed_at IS NULL` on `count_statuses` (NEW/FAILED form) and `count_partitions` | can A serve counts without D? `count_statuses` with COMPLETED keeps the original text |
| `UABCD2-NS` | as UABCD2, no statistics object | U | V1-3 under partial indexes (estimate on `completed_at IS NULL` comes from the partial index) |

### partition_no in the key (user idea 4; expected no gain or slower)

Each compared with its INCLUDE counterpart (`UABCD2`), same other indexes:
| Variant | Change | Expectation |
|---|---|---|
| `PA1` | A → `(status, partition_no, created_at, id) INCLUDE (next_retry_at, record_key)` partial | breaks global `ORDER BY created_at`: Sort over all due rows of the owned partitions; S4 much worse, but under partial ownership it skips foreign partitions: must be judged on S8/S16 |
| `PA2` | A → `(status, created_at, id, partition_no) INCLUDE (next_retry_at, record_key)` partial | same plan as A, filter on key column; no gain, size neutral |
| `PC1` | C → `(status, partition_no, next_retry_at)` partial | `oldest_due_*` per partition: MIN must scan or merge across partitions; S8 maybe better |
| `PC2` | C → `(status, next_retry_at, partition_no)` partial | same plan as C; no gain |

## Measurements

- Write cost on S2 for every DDL variant (latency, WAL bytes/write, index count and sizes); repeat for `UABCD2`, `UAB1`.
- Equivalence vs v1 on S2, S5, S7 (`pages` per-key order compared after the client sort).
- S9 churn and S10 (pollers 1, 4, rate 0, twice) for `v1b`, `UAB1`, `UABC`, `UABCD2`, plus the best from round 2.
- Plan-cache counts as in round 2; generic-plan run for `UABCD2` and `UAB1` on S2, S4, S7.

## Reports

`experiments/U-index-set.md` (per index: which P1/P2 query uses it, read gain, write cost, keep/drop), `experiments/U-pages-client-sort.md`,
`experiments/PA-partition-key.md`. Final recommended P1/P2 index set with evidence.

## Round 4 candidates

E40 UUIDv7, E07 autovacuum/fillfactor, E08 per-partition `LATERAL`, statement-cache effect of `IN` lists.
