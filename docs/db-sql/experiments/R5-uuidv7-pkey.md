# R5 follow-up: why is the UUIDv7 primary key larger than the UUIDv4 one?

Diagnosis only. No harness source, result or recommendation file was changed. PostgreSQL 18.6 (`postgres:18-alpine`, musl), scenario S2 (1 000 000 rows),
seed 42, scratch schemas only (all dropped at the end). Scripts: `/tmp/claude-1000/-home-zamo-git-namastack-outbox/d42f9bf0-f980-44e6-84c4-8250b4a2d3e0/scratchpad/`
(`prep.sh`, `q2.sql`, `q2b.sql`, `q2c.sql`, `q4.sh`, `wal2.sh`, `wal3.sh`, `j2.jsh`). "Measured" and "inferred" are kept apart below.

## Summary

* Measured: the gap exists straight after `prepare` (76.8 vs 97.3 MB, same as `results/UABC-L|E40/postgres/S2-wc-v1-R5/writecost.csv`).
  Key and pivot sizes are identical (48 B leaf tuples). The difference is only page fill: v7 leaf pages hold 86 tuples on average (88 % of them 79, i.e. a 50/50 split), v4 109, a monotone v7 build 141.
* Measured: the v7 ids are inserted into the PK in the heap order of the snapshot table `snap.rec`. That order is **not** created_at order: about 0.6 % of rows (6 245) sit before rows with a smaller created_at,
  some by up to 933 000 positions. Each such row carries a far-future v7 id, so for most of the load the largest key in the index is an outlier and new (smaller) ids are no longer appended at the right edge. Splits become 50/50.
* Measured: with the same ids inserted in created_at order, or as 1 M real row-by-row inserts (PostgreSQL 18 `uuidv7()`, 1 and 4 writers), the v7 PK is 56.3 MB (leaf density 89.9 %), 24 % smaller than the v4 PK (73.6-73.9 MB). Bulk `ADD PRIMARY KEY` gives 56.3 MB for both.
* Conclusion: the size gap is a harness artefact (load order), not production behaviour. The WAL figures are not affected (section 5).
* Inferred, not tested: why the snapshot heap order is scrambled (section 4).

## 1. Is the gap there right after `prepare`?

Yes. `harness/bin/db.sh start postgres`, `bin/bench gen --dialect postgres --scenario S2` (v4), then `HARNESS_ID_VERSION=7 bin/bench gen ...` (v7). `prepare` was replicated in SQL
(`prep.sh`: table with inline `PRIMARY KEY (id)`, `INSERT ... SELECT` from `snap.rec`, three secondary indexes after, `VACUUM (ANALYZE)`), same statement order as `Loader.prepare` (`Gen.kt` ~293-330).
The primary key therefore exists while the rows are loaded (incremental b-tree inserts in the scan order of `snap.rec`), and the secondary indexes are built afterwards.

| index | bytes | tree level | internal / leaf pages | avg leaf density | leaf fragmentation |
|---|---|---|---|---|---|
| v4 pkey | 76 808 192 | 2 | 89 / 9 286 | 69.33 | 49.84 |
| v7 pkey | 97 288 192 | 2 | 108 / 11 767 | 54.85 | 1.56 |

(`SELECT * FROM pgstatindex('scr_v4.outbox_record_pkey')`; heap sizes are identical, the other indexes are identical.)

## 2. What differs inside the index

`pageinspect` (`bt_page_stats` over every page, `q2c.sql`):

| index | type | pages | avg items/page | min-max | avg item bytes | avg free bytes |
|---|---|---|---|---|---|---|
| v4, harness order | leaf | 9 286 | 108.7 | 79-156 | 48.0 | 2 496 |
| v7, harness order | leaf | 11 767 | 86.0 | 79-156 | 48.0 | 3 677 |
| v7, monotone | leaf | 7 143 | 141.0 | 120-141 | 48.0 | 816 |
| v7, harness order | internal | 107 | 111.0 | 107-111 | 47.0 | 2 418 |

v7 leaf pages in harness order: 10 354 have 60-79 items (exact 50/50 split of a 156-item page), 1 268 have 120+. Physical order matches logical order (fragmentation 1.6 %), so this is not scatter but half-empty pages.
Pivots are not larger. The only difference is fill.

## 3. Collation

`datcollate = en_US.utf8`, libc provider, but the image is musl: `'B' < 'a'`, and `ORDER BY a` equals `ORDER BY a COLLATE "C"` for a mixed-case/punctuation sample. Scratch PK-only tables (`q2.sql` for default and `COLLATE "C"`; the ICU tables were created inline with the same `INSERT ... SELECT`, `COLLATE "en-US-x-icu"`):

| load | default collation | `COLLATE "C"` | `COLLATE "en-US-x-icu"` |
|---|---|---|---|
| v4, snapshot order | 76 808 192 | 76 808 192 | 76 808 192 |
| v7, snapshot order | 97 288 192 | 97 288 192 | 97 288 192 |
| v7, created_at order | 59 064 320 | - | 59 064 320 |

Collation is not the cause here. Not tested: glibc `en_US.UTF-8` (not in this image); ICU and C order agree for these lowercase-hex ids, so a change is unlikely but open.

## 4. Are the generated ids what a real generator gives? Snapshot order

Measured, from `snap.rec` (v7): id timestamp = floor(created_at ms) for all 1 M rows, version nibble 7, variant bits ok. Differences from RFC 9562 generators:

* The 12-bit field is `uuidV7(c, a, b)`'s `counter = a & 0xfff` where `a` is a random 64-bit value (`Gen.kt:215`), not a per-ms monotone counter. 16 001 rows share a millisecond with another row (1.6 %), their order inside the ms is random. This is harmless: ids sorted by created_at, ties by position, load at 89.94 % density.
* `Params.insertParams` (`Params.kt:87-88`): all writecost/concurrency/churn inserts use `createdMicros = T0_MICROS` (same ms prefix, above every existing id) and counter `= seq & 0xfff`, tail from the v4 hash. They form ascending runs of 4096 inside one ms block, beyond the maximum existing id, so they hit a few right-edge pages. Near what a real writer does, except for the fixed millisecond.
* Insert order: only 14 217 of 1 M ids are below their physical predecessor, but 6 245 rows (0.62 %) are placed before rows with a smaller created_at: 5 585 by 100+ positions, 1 998 by 1 000+, 1 055 by 10 000+, max 933 300 (`rk` ranking query: `row_number() over (order by ctid)` vs `over (order by created_at, ctid)`). Example: a row created 12:04:40 sits in the heap at the position of 12:02:21, 76 rows too early.
* The generator itself emits created_at order. `jshell` on the built jar (`j2.jsh`, `Realisation(...).forEach`): `created < prev` only 9 times; the example row is emitted at index 154, the table has it at index 78. So the reordering happens in PostgreSQL, between `COPY` and the heap.
* Measured trigger: COPY of a file written in strict created_at order into a heap table. 300 000 rows, only fixed-size columns: 0 inversions. Same with the nullable `context` column (row size 576-680 B): 366 inversions, max displacement 44 073. Full 1 M rows (id, payload, context, created_at): 1 171 inversions, max displacement 705 045. `INSERT ... SELECT ... ORDER BY created_at` also reorders slightly (8 998 inversions, max 201). CTAS of 300 k rows does not. Rows of varying width are placed by PostgreSQL in other pages (free-space map, bulk extension) rather than appended: **inferred**, the FSM mechanism itself was not isolated; the dependence on row-size variance was.
* Effect on the heap is small (`pg_stats.correlation` of created_at is 1.0000). Effect on the unique index is large because `prepare` feeds ids in scan order.

Ablation (measured, `q2b.sql`): load v7 ids in snapshot order but leave out the early-placed rows:

| rows left out | rows kept | PK bytes | leaf density |
|---|---|---|---|
| none | 1 000 000 | 97 288 192 | 54.85 |
| displaced early by >= 10 000 | 998 945 | 61 399 040 | 86.46 |
| displaced early by >= 1 000 | 998 002 | 58 949 632 | 89.94 |
| displaced early by >= 100 | 994 415 | 58 736 640 | 89.94 |
| any early (6 245) | 993 755 | 58 703 872 | 89.93 |

Load order alternatives on the full set (`q2.sql`): created_at order 56.3 MB (89.94 %), sorted by id 56.3 MB, snapshot order 92.8 MB. The outliers alone (0.1-0.6 % of rows) account for the whole gap.
Why outliers cost 35 points of density: **inferred** from the 50/50 page pattern (79 of 156 items) - while the right-most leaf's last key is a future outlier, new keys are not appended past the end, so `_bt_findsplitloc` does not use the 90 % right-edge split. The 50/50 pattern is measured; the code path is not.

## 5. What production sees, and the WAL figures

Row-by-row inserts, committed after each row (`q4.sh`: `INSERT INTO t VALUES (uuidv7()::text)` / `gen_random_uuid()::text`, PK-only table, `synchronous_commit=off`), 1 000 000 rows:

| writers | v4 | v7 (PostgreSQL 18 `uuidv7()`) |
|---|---|---|
| 1 | 77 455 360 B, density 68.74 % | 59 064 320 B, 89.94 % |
| 4 (250 k each) | 77 152 256 B, 69.01 % | 59 064 320 B, 89.94 % |
| bulk `ADD PRIMARY KEY` after load | 59 064 320 B, 89.94 % | 59 064 320 B, 89.94 % |

So a v7 key is 24 % smaller than v4 when inserted in time order, and the same size as a bulk-built index. Not tested: more than 4 writers, large clock skew between writers.

WAL (`wal2.sh`, `wal3.sh`; same row shape as writecost: record_key 32 B (`order-bench-` + 20 digits), handler ~120 B, payload 500 B, context alternating; `CHECKPOINT` before each phase, 5 000 committed inserts, 1 000 committed completes of the original due rows, full tables with the 3 secondary indexes). `scr_v7` = harness load (97 MB PK), `scr_v7s` = same data loaded in created_at order (59 MB PK):

| bytes WAL per op | v4 | v7 harness load | v7 sorted load |
|---|---|---|---|
| insert (5 000) | 6 247 | 1 813 | 1 774 |
| upd_complete (repeat 2) | 6 600 | 1 364 | 1 360 |
| upd_complete (repeat 3) | 7 078 | 1 383 | 1 357 |

Harness values in `S2-wc-v1-R5`: insert 6 198 (v4) / 1 562 (v7), upd_complete 6 295 / 1 987. These are reproduced; PK density does not change them (1 813 vs 1 774, 1 383 vs 1 357). `pg_walinspect` for the v7 insert phase: the v7 cost is mostly `Btree/INSERT_LEAF` records (3 296 records, 374 kB, 71 kB of page images) plus about 62 B of `Heap/INSERT` record data; v4 pays a full-page image per touched random leaf page after each checkpoint (not measured with `pg_walinspect` for v4, inferred from the 6.2 kB per insert). The WAL gap comes from locality (time-ordered ids hit the hot right edge, random ids a new leaf page after each checkpoint), not from the page fill. Discarded: the first `upd_complete` repeat (49-66 kB/op for all three, rows in a different state after my earlier runs; not explained).

## 6. Answer

* Cause: load order. `prepare` builds the PK incrementally from `snap.rec`, whose physical order differs from created_at order for 0.6 % of rows (variable-width rows are placed by PostgreSQL out of sequence during `COPY`). Their future-dated v7 ids sit above everything inserted for a long time, splits become 50/50, leaf density falls to 55 %.
* Production vs harness: artefact. With ids inserted in generation order a v7 PK is 56 MB (90 % dense), smaller than the v4 PK (74 MB). A restored/ordered load shows the same.
* WAL figures: not undermined. Insert and update WAL are unchanged between the scrambled and the sorted v7 PK.
* Open: the free-space-map explanation for the scrambled heap order (trigger measured, mechanism not); the exact b-tree split rule responsible for 50/50 splits (pattern measured, code path inferred); glibc `en_US.UTF-8`; more than 4 concurrent writers. Other R5 conclusions that depend on the v7 PK size (index size, buffer cache footprint, S9 "all indexes 76 vs 97 MB") are affected; the churn run kept the prepared index, so its 97 MB inherits the same artefact. Not re-measured.
* Fix suggestions (not applied, harness sources untouched): `INSERT ... SELECT ... ORDER BY created_at, id` in `Loader.prepare`, or create the PK after the load (bulk build), for v7.
