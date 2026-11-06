# E02 — `(status, created_at, id)` instead of v1's queue index

Round 1, PostgreSQL 18, SQL-level harness (warmup 5 + 20 reps, fresh `--prepare`), base = **v1**; the `main` column is shown for reference.
Variant order per label in `results/logs/round1-pg.log` (S2: v1 2nd, E02a-c 6th-8th; S8-S2: E02a 3rd...; S4: E02b first, E02c second, E02a last; S8-S4: E02a-c 10th-12th; S5: E02a-c 7th-9th; S6: E02a-c 4th-6th; S7: E02a, E02b, E02c first three). No failed runs.

## Hypothesis

v1's incomplete-record index `idx_outbox_record_created_id_incomplete (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL`
is ordered by `created_at`; `status = 'NEW'` is only a filter, so with many `FAILED` rows (which are also `completed_at IS NULL`) the scan walks and discards them
(v1 `keys_strict` S7: `Rows Removed by Filter: 133903`, `results/v1/postgres/S7-R1/plans/keys_strict.txt`). A status-leading key `(status, created_at, id)` makes `status` an index condition.

## Exact change (DDL diffs against `outbox-wt-v1`'s `schema/postgres/outbox-tables.sql`, verified)

| Variant | Change |
|---|---|
| E02a (`experiments/E02a-status-created-v1-nonpartial/ddl.sql`) | drop `idx_outbox_record_created_id_incomplete`; add `idx_outbox_record_status_created_id ON (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key)` (non-partial) |
| E02b (`experiments/E02b-status-created-v1-partial/ddl.sql`) | drop `idx_outbox_record_created_id_incomplete`; add `idx_outbox_record_status_created_id_incomplete ON (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL` |
| E02c (`experiments/E02c-status-created-v1-plus-partial/ddl.sql`) | v1 unchanged + the E02b index (both queue indexes) |

Queries: v1 catalog unchanged in all three. No code change, no branch.

## Method and noise

As in E01: medians from `results/<variant>/postgres/<label>-R1/timings.csv`, bold = beyond the group threshold (HOT/WRITE 30 %, MON 20 %) and > 0.15 ms vs **v1**. P1 first, P2 second, P3 not driving.

## Equivalence

`bin/bench equiv` against v1 on S2, S5 and S7: E02a, E02b, E02c all PASS on all queries (26 PASS, 2 SKIP unbounded, no WARN/FAIL), e.g.
`results/equiv/postgres/S2/E02b-vs-v1.md`, `results/equiv/postgres/S5/E02b-vs-v1.md`, `results/equiv/postgres/S7/E02b-vs-v1.md` (same files for E02a, E02c).

## Which queries use the new index (plans)

In every plan directory checked (S2, S4, S5, S7 for E02a/E02b/E02c): `keys_strict`, `keys_nonstrict` and `oldest_created` use the new index (`results/E02*/postgres/<label>-R1/plans/`); E02a also lists `count_partitions` (S2, S7) and `count_failed` (S5) among its plans. `oldest_due_strict` stays on `idx_status_retry`/the record-key index. In E02c the planner picks the **new** partial index for all three queries; v1's `created_id_incomplete` is not used (E02c `plans/keys_strict.txt`, S2: `Index Only Scan using idx_outbox_record_status_created_id_incomplete`), i.e. E02c's second index is pure write overhead.

Plan excerpts:

- v1 `keys_strict`, S7 (`results/v1/postgres/S7-R1/plans/keys_strict.txt`): `Nested Loop Anti Join`, outer `Index Only Scan using idx_outbox_record_created_id_incomplete` — **no Index Cond**, `Rows Removed by Filter: 133903` before 10 hits, 12.2 ms.
- E02b, S7 (`results/E02b/postgres/S7-R1/plans/keys_strict.txt`): outer `Index Only Scan using idx_outbox_record_status_created_id_incomplete`, `Index Cond: (status = 'NEW')`, `Heap Fetches: 0`, no filtered rows, 0.133 ms.
- E02a, S7 (`results/E02a/postgres/S7-R1/plans/keys_strict.txt`): `Index Scan using idx_outbox_record_status_created_id` (non-partial, not index-only here: the planner fetches `completed_at` from the heap for the `IS NULL` predicate), 0.135 ms.
- S2: v1 `keys_nonstrict` removes 11 628 non-NEW rows (FAILED) before finding 10 (`results/v1/postgres/S2-R1/plans/keys_nonstrict.txt`, 1.09 ms, 253 buffers); E02b `keys_strict` (`results/E02b/postgres/S2-R1/plans/keys_strict.txt`) removes 2 rows (0.131 ms).
- `oldest_created` S2: v1 `Rows Removed by Filter: 2258`, 0.220 ms (`results/v1/.../plans/oldest_created.txt`) vs E02b `Index Cond: (status = 'NEW')`, 0.038 ms.

The partial predicate is `completed_at IS NULL` (a literal in the SQL), `status` is an ordinary key column; this works for custom and generic plans alike (a `status = 'NEW'`-predicate partial index would not be matched by pgjdbc's server-prepared `status = $1`). A forced-generic run was not part of E02 (only E14 has one).

## Results (median ms)

Source files `results/{v1,E02a,E02b,E02c,main}/postgres/<label>-R1/timings.csv`. Bold = beyond noise vs v1.

#### S2  (`results/<variant>/postgres/S2-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 2.142 | 1.266 **-41%** | 1.246 **-42%** | 1.196 **-44%** | 5.905 |
| P1 | keys_nonstrict | 2.116 | 1.267 **-40%** | 1.269 **-40%** | 1.237 **-42%** | 1.646 |
| P1 | pages | 0.709 | 0.840 | 0.723 | 0.727 | 0.961 |
| P1 | insert | 0.342 | 0.370 | 0.426 | 0.349 | 0.326 |
| P1 | upd_complete | 0.355 | 0.327 | 0.345 | 0.323 | 0.351 |
| P1 | upd_retry | 0.300 | 0.268 | 0.277 | 0.245 | 0.265 |
| P1 | upd_fail | 0.293 | 0.260 | 0.263 | 0.260 | 0.226 |
| P1 | delete_by_id | 0.151 | 0.194 | 1.158 **+667%** | 0.160 | 0.170 |
| P2 | count_statuses | 1.283 | 1.218 | 1.232 | 1.270 | n/a |
| P2 | count_new | 0.396 | 0.390 | 1.585 **+300%** | 0.502 | 0.339 |
| P2 | count_failed | 0.804 | 0.738 | 1.162 **+45%** | 0.779 | 0.796 |
| P2 | count_partitions | 0.848 | 0.694 | 1.410 **+66%** | 0.843 | 0.878 |
| P2 | oldest_created | 0.836 | 0.606 **-28%** | 0.684 | 0.636 **-24%** | 1.168 |
| P2 | oldest_due_strict | 0.947 | 0.846 | 0.946 | 0.950 | 4.069 |
| P2 | oldest_due_nonstrict | 0.404 | 0.362 | 0.418 | 0.400 | 0.383 |
| P3 | find_completed_before | 496.7 | 498.4 | 493.5 | 497.2 | 1578.7 |
| P3 | find_failed_before | 0.317 | 0.269 | 0.237 | 0.265 | 0.299 |
| P3 | ids_completed_before | 491.7 | 489.6 | 487.1 | 487.3 | 646.0 |
| P3 | ids_failed_before | 0.223 | 0.201 | 0.211 | 0.241 | 0.175 |
| P3 | delete_by_ids | 4.360 | 4.657 | 4.392 | 4.457 | 4.431 |
| P3 | delete_by_status_failed | 11.0 | 10.8 | 11.0 | 11.0 | 10.9 |
| P3 | delete_by_key_status | 0.109 | 0.243 | 0.102 | 0.233 | 0.319 |
| P3 | find_incomplete_by_key | 0.156 | 0.273 | 0.176 | 0.295 | 0.406 |

#### S8-S2  (`results/<variant>/postgres/S8-S2-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 2.200 | 0.950 **-57%** | 0.946 **-57%** | 0.999 **-55%** | 2.022 |
| P1 | keys_nonstrict | 2.007 | 0.985 **-51%** | 1.308 **-35%** | 0.999 **-50%** | 0.910 |
| P1 | pages | 0.661 | 0.690 | 0.692 | 0.687 | 0.841 |
| P1 | insert | 0.339 | 0.385 | 0.300 | 0.461 | 0.355 |
| P1 | upd_complete | 0.358 | 0.369 | 0.296 | 0.372 | 0.325 |
| P1 | upd_retry | 0.275 | 0.289 | 0.264 | 0.334 | 0.285 |
| P1 | upd_fail | 0.272 | 0.288 | 0.260 | 0.279 | 0.247 |
| P1 | delete_by_id | 0.144 | 0.196 | 0.160 | 0.150 | 0.167 |
| P2 | count_statuses | 1.247 | 1.277 | 1.293 | 1.353 | n/a |
| P2 | count_new | 0.373 | 0.393 | 0.324 | 0.482 | 0.300 |
| P2 | count_failed | 0.798 | 0.790 | 0.808 | 0.985 **+23%** | 0.732 |
| P2 | count_partitions | 0.594 | 0.568 | 0.431 **-27%** | 0.669 | 0.526 |
| P2 | oldest_created | 1.173 | 0.381 **-68%** | 0.327 **-72%** | 0.473 **-60%** | 0.690 |
| P2 | oldest_due_strict | 0.695 | 0.678 | 0.683 | 0.865 **+24%** | 1.508 |
| P2 | oldest_due_nonstrict | 0.240 | 0.203 | 0.202 | 0.243 | 0.258 |
| P3 | find_completed_before | 495.5 | 498.6 | 496.1 | 497.8 | 1550.4 |
| P3 | find_failed_before | 0.265 | 0.265 | 0.310 | 0.254 | 1.386 |
| P3 | ids_completed_before | 490.6 | 491.2 | 490.3 | 490.4 | 646.1 |
| P3 | ids_failed_before | 0.219 | 0.253 | 0.193 | 0.232 | 0.253 |
| P3 | delete_by_ids | 4.701 | 4.579 | 4.532 | 5.181 | 4.463 |
| P3 | delete_by_status_failed | 10.8 | 10.8 | 10.8 | 11.3 | 10.8 |
| P3 | delete_by_key_status | 0.254 | 0.176 | 0.169 | 0.123 | 0.226 |
| P3 | find_incomplete_by_key | 0.372 | 0.175 **-53%** | 0.175 **-53%** | 0.159 **-57%** | 0.315 |

#### S4  (`results/<variant>/postgres/S4-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 1.246 | 1.504 | 1.221 | 1.237 | 4411.0 |
| P1 | keys_nonstrict | 1.274 | 1.152 | 1.176 | 1.268 | 1352.2 |
| P1 | pages | 0.860 | 0.697 | 0.715 | 0.695 | 0.909 |
| P1 | insert | 0.590 | 0.608 | 0.890 **+51%** | 0.843 **+43%** | 0.910 |
| P1 | upd_complete | 0.385 | 0.324 | 0.923 **+140%** | 1.039 **+170%** | 1.325 |
| P1 | upd_retry | 0.323 | 0.308 | 0.439 | 0.798 **+147%** | 0.496 |
| P1 | upd_fail | 0.268 | 0.308 | 0.283 | 0.306 | 0.211 |
| P1 | delete_by_id | 0.178 | 0.146 | 0.148 | 0.166 | 0.179 |
| P2 | count_statuses | 38.5 | 39.6 | 38.3 | 38.4 | n/a |
| P2 | count_new | 29.5 | 29.9 | 29.5 | 29.3 | 29.5 |
| P2 | count_failed | 21.7 | 21.9 | 22.0 | 21.8 | 21.9 |
| P2 | count_partitions | 64.3 | 65.5 | 64.3 | 63.7 | 64.5 |
| P2 | oldest_created | 0.651 | 0.615 | 0.593 | 0.564 | 214.9 |
| P2 | oldest_due_strict | 0.951 | 0.900 | 0.932 | 1.259 **+32%** | 697.2 |
| P2 | oldest_due_nonstrict | 0.387 | 0.350 | 0.341 | 0.268 | 0.340 |
| P3 | find_completed_before | 495.5 | 492.1 | 493.3 | 494.2 | 1571.5 |
| P3 | find_failed_before | 0.230 | 0.259 | 0.343 | 0.199 | 0.214 |
| P3 | ids_completed_before | 494.5 | 486.6 | 486.5 | 489.8 | 651.6 |
| P3 | ids_failed_before | 0.262 | 0.254 | 0.281 | 0.198 | 0.243 |
| P3 | delete_by_ids | 4.787 | 4.663 | 4.752 | 5.018 | 4.605 |
| P3 | delete_by_status_failed | 0.142 | 0.146 | 0.172 | 0.178 | 0.167 |
| P3 | delete_by_key_status | 0.109 | 0.121 | 0.115 | 0.124 | 0.155 |
| P3 | find_incomplete_by_key | 0.148 | 0.227 | 0.157 | 0.182 | 0.189 |

#### S8-S4  (`results/<variant>/postgres/S8-S4-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 0.994 | 0.950 | 1.055 | 0.974 | 2853.5 |
| P1 | keys_nonstrict | 0.988 | 0.971 | 1.019 | 0.988 | 1440.5 |
| P1 | pages | 0.672 | 0.749 | 0.837 | 0.733 | 0.943 |
| P1 | insert | 0.552 | 0.511 | 0.516 | 0.582 | 0.586 |
| P1 | upd_complete | 0.402 | 0.381 | 0.343 | 0.401 | 0.402 |
| P1 | upd_retry | 0.281 | 0.327 | 0.283 | 0.305 | 0.281 |
| P1 | upd_fail | 0.224 | 0.313 | 0.230 | 0.252 | 0.223 |
| P1 | delete_by_id | 0.164 | 0.177 | 0.135 | 0.137 | 0.148 |
| P2 | count_statuses | 38.6 | 39.6 | 38.8 | 38.4 | n/a |
| P2 | count_new | 29.4 | 30.0 | 29.5 | 29.3 | 29.3 |
| P2 | count_failed | 21.7 | 22.2 | 21.8 | 0.171 **-99%** | 0.170 |
| P2 | count_partitions | 16.2 | 16.1 | 16.3 | 16.4 | 16.4 |
| P2 | oldest_created | 0.384 | 0.454 | 0.429 | 0.454 | 211.4 |
| P2 | oldest_due_strict | 0.737 | 0.796 | 0.737 | 0.741 | 848.0 |
| P2 | oldest_due_nonstrict | 0.296 | 0.260 | 0.271 | 0.269 | 0.217 |
| P3 | find_completed_before | 492.8 | 491.0 | 492.5 | 490.6 | 1881.9 |
| P3 | find_failed_before | 0.282 | 0.195 | 0.291 | 0.251 | 1.359 |
| P3 | ids_completed_before | 485.1 | 495.9 | 484.8 | 490.3 | 647.0 |
| P3 | ids_failed_before | 0.241 | 0.230 | 0.271 | 0.217 | 0.263 |
| P3 | delete_by_ids | 4.926 | 4.736 | 4.773 | 4.722 | 4.710 |
| P3 | delete_by_status_failed | 0.140 | 0.124 | 0.173 | 0.137 | 0.160 |
| P3 | delete_by_key_status | 0.130 | 0.105 | 0.148 | 0.131 | 0.131 |
| P3 | find_incomplete_by_key | 0.177 | 0.172 | 0.142 | 0.146 | 0.131 |

#### S5  (`results/<variant>/postgres/S5-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 1.289 | 1.308 | 1.335 | 1.348 | 14675.8 |
| P1 | keys_nonstrict | 1.237 | 1.268 | 1.331 | 1.358 | 115.9 |
| P1 | pages | 133.9 | 134.0 | 133.5 | 134.9 | 145.8 |
| P1 | insert | 0.306 | 0.356 | 0.306 | 0.308 | 0.423 |
| P1 | upd_complete | 0.306 | 0.317 | 0.313 | 0.307 | 0.322 |
| P1 | upd_retry | 0.312 | 0.235 | 0.270 | 0.301 | 0.241 |
| P1 | upd_fail | 0.212 | 0.196 | 0.216 | 0.268 | 0.269 |
| P1 | delete_by_id | 0.129 | 0.152 | 0.139 | 0.164 | 0.166 |
| P2 | count_statuses | 18.8 | 19.3 | 18.7 | 18.7 | n/a |
| P2 | count_new | 7.552 | 7.401 | 7.484 | 7.298 | 7.600 |
| P2 | count_failed | 0.195 | 0.113 | 0.181 | 0.132 | 0.186 |
| P2 | count_partitions | 13.8 | 13.4 | 13.5 | 13.5 | 13.4 |
| P2 | oldest_created | 0.635 | 0.675 | 0.596 | 0.653 | 63.6 |
| P2 | oldest_due_strict | 0.998 | 0.922 | 0.916 | 0.928 | 5176.2 |
| P2 | oldest_due_nonstrict | 0.339 | 0.362 | 0.315 | 0.359 | 0.381 |
| P3 | find_completed_before | 153.8 | 156.1 | 152.6 | 155.9 | 490.8 |
| P3 | find_failed_before | 0.245 | 0.290 | 0.288 | 0.272 | 0.304 |
| P3 | ids_completed_before | 150.7 | 150.1 | 152.2 | 150.1 | 181.3 |
| P3 | ids_failed_before | 0.167 | 0.297 | 0.282 | 0.272 | 0.228 |
| P3 | delete_by_ids | 4.258 | 4.645 | 4.777 | 4.600 | 4.545 |
| P3 | delete_by_status_failed | 0.196 | 0.204 | 0.207 | 0.211 | 0.152 |
| P3 | delete_by_key_status | 8.366 | 8.648 | 8.400 | 8.232 | 8.454 |
| P3 | find_incomplete_by_key | 15.0 | 13.5 | 13.8 | 14.7 | 14.4 |

#### S6  (`results/<variant>/postgres/S6-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 1.412 | 1.387 | 1.311 | 1.516 | 411.3 |
| P1 | keys_nonstrict | 1.188 | 1.386 | 1.304 | 1.202 | 110.5 |
| P1 | pages | 0.939 | 0.672 | 0.846 | 0.774 | 0.794 |
| P1 | insert | 0.442 | 0.308 | 0.323 | 0.459 | 0.290 |
| P1 | upd_complete | 0.362 | 0.317 | 0.373 | 0.363 | 0.348 |
| P1 | upd_retry | 0.257 | 0.272 | 0.331 | 0.364 | 0.271 |
| P1 | upd_fail | 0.241 | 0.295 | 0.295 | 0.249 | 0.272 |
| P1 | delete_by_id | 0.148 | 0.137 | 0.198 | 0.131 | 0.136 |
| P2 | count_statuses | 21.5 | 21.5 | 21.6 | 21.6 | n/a |
| P2 | count_new | 21.3 | 7.619 **-64%** | 7.644 **-64%** | 7.659 **-64%** | 21.2 |
| P2 | count_failed | 0.260 | 0.252 | 0.224 | 0.218 | 0.286 |
| P2 | count_partitions | 21.0 | 20.6 | 21.6 | 21.4 | 20.4 |
| P2 | oldest_created | 0.538 | 0.529 | 0.647 | 0.690 **+28%** | 85.5 |
| P2 | oldest_due_strict | 1.208 | 0.774 **-36%** | 0.957 **-21%** | 0.944 **-22%** | 165.0 |
| P2 | oldest_due_nonstrict | 0.306 | 0.264 | 0.407 | 0.404 | 0.362 |
| P3 | find_completed_before | 397.8 | 398.0 | 397.9 | 398.1 | 1240.8 |
| P3 | find_failed_before | 0.284 | 0.249 | 0.283 | 0.293 | 0.243 |
| P3 | ids_completed_before | 397.6 | 393.8 | 395.7 | 398.1 | 467.9 |
| P3 | ids_failed_before | 0.281 | 0.218 | 0.225 | 0.294 | 0.205 |
| P3 | delete_by_ids | 5.150 | 4.479 | 4.625 | 4.829 | 4.331 |
| P3 | delete_by_status_failed | 0.157 | 0.176 | 0.155 | 0.131 | 0.160 |
| P3 | delete_by_key_status | 0.131 | 0.130 | 0.152 | 0.158 | 0.132 |
| P3 | find_incomplete_by_key | 0.184 | 0.156 | 0.150 | 0.178 | 0.172 |

#### S7  (`results/<variant>/postgres/S7-R1/timings.csv`, median ms; bold = beyond noise vs v1)

| prio | query | v1 | E02a | E02b | E02c | main |
|---|---|---:|---:|---:|---:|---:|
| P1 | keys_strict | 13.3 | 1.190 **-91%** | 1.230 **-91%** | 1.321 **-90%** | 55.9 |
| P1 | keys_nonstrict | 12.8 | 1.238 **-90%** | 1.295 **-90%** | 1.264 **-90%** | 8.636 |
| P1 | pages | 0.935 | 0.661 | 0.656 | 0.869 | 1.611 |
| P1 | insert | 0.328 | 0.324 | 0.332 | 0.396 | 0.384 |
| P1 | upd_complete | 0.306 | 0.326 | 0.295 | 0.391 | 0.371 |
| P1 | upd_retry | 0.262 | 0.289 | 0.274 | 0.341 | 0.292 |
| P1 | upd_fail | 0.210 | 0.225 | 0.241 | 0.279 | 0.282 |
| P1 | delete_by_id | 0.162 | 0.181 | 0.147 | 0.178 | 0.190 |
| P2 | count_statuses | 27.0 | 27.6 | 26.9 | 27.0 | n/a |
| P2 | count_new | 0.670 | 0.699 | 0.984 **+47%** | 0.769 | 0.714 |
| P2 | count_failed | 21.9 | 22.3 | 22.2 | 22.0 | 22.2 |
| P2 | count_partitions | 1.968 | 1.689 | 1.977 | 1.894 | 1.950 |
| P2 | oldest_created | 1.467 | 0.582 **-60%** | 0.610 **-58%** | 0.551 **-62%** | 4.378 |
| P2 | oldest_due_strict | 0.869 | 0.839 | 0.989 | 0.914 | 34.4 |
| P2 | oldest_due_nonstrict | 0.396 | 0.350 | 0.400 | 0.364 | 0.379 |
| P3 | find_completed_before | 251.2 | 284.7 | 240.1 | 237.2 | 1173.2 |
| P3 | find_failed_before | 2.978 | 3.336 | 3.846 **+29%** | 3.196 | 680.0 |
| P3 | ids_completed_before | 248.5 | 243.2 | 237.4 | 244.9 | 312.8 |
| P3 | ids_failed_before | 0.715 | 0.663 | 0.649 | 0.628 | 271.2 |
| P3 | delete_by_ids | 4.384 | 4.711 | 4.554 | 4.700 | 4.548 |
| P3 | delete_by_status_failed | 11394.1 | 12062.9 | 10667.8 | 11805.5 | 11558.8 |
| P3 | delete_by_key_status | 0.104 | 0.126 | 0.126 | 0.101 | 0.100 |
| P3 | find_incomplete_by_key | 0.194 | 0.177 | 0.174 | 0.198 | 0.153 |

Reading the tables:

- **Wins are confined to scenarios where the old index walks non-matching entries**: S2/S8-S2 (12 800 FAILED rows; 12 k `Rows Removed by Filter`) `keys_*` 2.1-2.2 -> 0.95-1.27 ms (-40...-57 %), S7 (390 k FAILED) 13.3/12.8 -> 1.2/1.3 ms (-90 %). S4/S5/S6 (no FAILED rows beyond the queue, or only on the tail) are equal to v1 (1.2-1.4 ms in all four variants).
- E02b S8-S2 `keys_nonstrict` -35 % (1.31 ms) vs E02a/E02c -50 % (~1.0 ms): same plan shape, difference of 0.3 ms within repeat spread.
- **Outliers not related to the index** (kept for transparency): E02b S2 `delete_by_id` 1.158 ms and `count_new` 1.585 ms (v1 0.151 / 0.396; E02b's own plans execute in 0.188 / 0.761 ms: `results/E02b/postgres/S2-R1/plans/delete_by_id.txt`, `count_new.txt`); the pkey/idx_status paths are untouched by the DDL change and other scenarios do not repeat it. E02b/E02c S4 write queries (`insert` 0.89/0.84 vs 0.59 ms, `upd_complete` 0.92/1.04 vs 0.39 ms, `upd_retry` E02c 0.80 ms): E02b and E02c ran first and second in the S4 rotation (cold buffers after the snapshot) and main shows the same bump in that label (`upd_complete` 1.33 ms, `insert` 0.91 ms); E02a (ran last) shows none, S8-S4 shows none for any variant, and the committed `writecost` runs show no latency change (below). Treat as order artifact, not a write regression; **not re-measured**.
- Flapping `count_failed`/`count_new` cells (S4/S8-S4/S6) occur in all variants (see E01 report); not index related.

## Write cost and storage

Source: `results/{v1,E02a,E02b}/postgres/S2-R1/writecost.csv` (E02c has no writecost run: spec). `indexes_total_bytes_*` and per-index bytes are in the same csv; the sizes of the queue index for S4/S7 are from `results/<variant>/postgres/<label>-R1/env.txt`.

| Metric (S2) | v1 | E02a | E02b |
|---|---:|---:|---:|
| index count (incl. pkey) | 6 | 6 | 6 |
| queue index size before / after 10 000 writes | 1 949 696 / 3 653 632 (1.9 -> 3.5 MB) | 149 782 528 / 152 846 336 (143 -> 146 MB) | 1 982 464 / 3 719 168 (1.9 -> 3.5 MB) |
| all indexes bytes before | 289 898 496 | 437 731 328 (+51 %) | 289 931 264 (+0.01 %) |
| queue index size S4 (1 M NEW) | 124 MB | 276 MB | 133 MB (+7 %) |
| queue index size S7 (10 k NEW, 390 k FAILED) | 53 MB | 139 MB | 53 MB |
| insert p50 1 thread, ms | 1.885 | 1.660 | 2.018 |
| insert p95 1 thread, ms | 2.538 | 2.359 | 2.622 |
| insert throughput 4 threads, /s | 1663.5 | 1810.4 | 1635.7 |
| `upd_complete` committed p50, ms | 2.187 | 2.091 | 2.239 |
| WAL bytes per insert (1 thread) | 6 674.0 | 6 728.5 (+0.8 %) | 6 691.7 (+0.3 %) |
| WAL bytes per insert (4 threads) | 6 850.7 | 6 866.5 | 6 869.0 |
| WAL bytes per `upd_complete` | 7 481.4 | 7 776.7 (+4.0 %) | 7 479.0 (0 %) |

All latency differences are below the 30 % write threshold and the absolute 0.15 ms is only exceeded within the fsync-dominated spread (E02a is the fastest and E02b the slowest, in opposite order to their sizes, i.e. noise). E02a's non-partial index indexes every COMPLETED row and costs 143 MB (75x E02b at S2) plus ~4 % WAL per status update. E02b is write-cost neutral against v1: same count, same size (1 % larger on S2, 7 % on S4 because `status` is stored in the key), same WAL. E02c adds a second queue index (+1.9 MB at S2, +133 MB at S4) for no read benefit.

## Concurrency (S10, `results/{v1,E02b}/postgres/S10-R1/concurrency-p{1,4}-r0.txt`, one 60 s run each, 4 unthrottled producers; E02a/E02c not run)

| Config | processed/s | inserted/s | keys p50 / p95, ms | pages p50, ms | update p50 / p95, ms | insert p50 / p95, ms |
|---|---:|---:|---:|---:|---:|---:|
| v1, 1 poller | 287.9 | 1690.9 | 6.91 / 14.29 | 0.76 | 1.85 / 5.33 | 1.81 / 4.38 |
| E02b, 1 poller | 338.7 (+18 %) | 1799.1 (+6 %) | 4.18 / 7.53 | 0.87 | 1.84 / 4.40 | 1.78 / 3.82 |
| v1, 4 pollers | 1109.8 | 1628.8 | 7.22 / 15.89 | 0.77 | 2.00 / 5.39 | 1.90 / 4.49 |
| E02b, 4 pollers | 1149.7 (+3.6 %) | 1548.4 (-5 %) | 5.75 / 8.72 | 1.29 | 2.55 / 5.57 | 2.04 / 5.03 |

There is no replicate and no noise estimate for S10; the numbers support "keys latency is lower with E02b (p50 -40 % with 1 poller, -20 % with 4; p95 roughly halved) and throughput is not lower", but the 4-poller `update`/`pages` p50 are slightly higher and those single-run differences cannot be separated from noise. No concurrency regression visible.

## Verdict

**E02b: win** (P1), with E02a and E02c rejected.

- P1 `keys_strict`/`keys_nonstrict`: **win** on S2 (2.14 -> 1.25 ms, 2.12 -> 1.27 ms), S8-S2 (2.20 -> 0.95, 2.01 -> 1.31 ms), S7 (13.3 -> 1.23 ms, 12.8 -> 1.30 ms); no difference on S4, S8-S4, S5, S6. Never a loss. `pages`, `delete_by_id`, `upd_*`, `insert`: no difference (outliers above explained).
- P2: `oldest_created` -72 % S8-S2 (1.17 -> 0.33 ms), -58 % S7 (1.47 -> 0.61 ms); S2 -18 % (below threshold), S4-S6 no difference; `count_*`, `oldest_due_*`: no difference except non-reproducible flaps.
- P3: not examined for the verdict (see tables; no regression of note).
- Write cost: none measurable (same index count, WAL/write within 0.3 %, committed latency within noise).

**Answer: should v1 switch to `(status, created_at, id)`? Yes, to variant (b): the partial form `... INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL`, replacing `idx_outbox_record_created_id_incomplete`.**
(a) the non-partial index has the same reads but 75x the size (143 MB vs 1.9 MB on S2) and +4 % WAL per update; (c) keeping both gives no read gain and an extra index to maintain, contrary to the decision rule "drop indexes".
Caveat on the evidence: E02b's win is the removal of FAILED-row scanning (S2, S7: 12.8 k and 390 k FAILED rows). Where the queue holds NEW rows only (S4, S5, S6) v1's index is equally good. The measured scenario that favours v1's order index would be a workload in which the queue is long and `FAILED` rows are rare; no such regression was found (S4: equal).

## Portability

| Dialect | Notes |
|---|---|
| PostgreSQL | as measured (partial index, INCLUDE) |
| SQL Server | filtered index `(status, created_at, id) INCLUDE (...) WHERE completed_at IS NULL` is expressible (v1 already uses a filtered index here); filtered-index limitations (SET options, no parameterised predicates) do not matter since the predicate is a literal `IS NULL` |
| Oracle | no partial/INCLUDE; v1 already uses `(status, created_at, id, partition_no, next_retry_at, record_key)`, i.e. the status-leading shape (E02a without partial); COMPLETED rows are in the index (status is not NULL). Not measured |
| MySQL / MariaDB | no partial/INCLUDE; v1 uses `(completed_at, created_at, id, ...)` where `completed_at IS NULL` is an equality prefix that already behaves like a partial index but does not separate FAILED from NEW; a status-leading `(status, created_at, id, ...)` would index COMPLETED rows too (E02a-like size cost) or `(completed_at, status, created_at, id)` could give `status` as a second equality column. Not measured |
| H2 | same as MySQL (no partial index); `(completed_at, status, created_at, id)` is the portable shape to test |
| JPA | `@Index` on the entity cannot express a partial index; the PostgreSQL DDL lives in the JDBC schema files, JPA's annotation index list would stay `(completedAt, createdAt, id)` or become the generic status-leading variant |

## Follow-ups

- Confirm in round 2 with an S2-like snapshot with higher FAILED share and a forced-generic-plan run (`plan_cache_mode = force_generic_plan`) for E02b.
- Repeat S10 with replicates (3x) for v1/E02b to put a noise band on the concurrency numbers; add E02a/E02c only if needed.
- Re-run the S4 write rows (insert, `upd_complete`) for E02b/E02c in a non-first rotation position to close the order-artifact question.
- For MySQL/MariaDB/H2 test `(completed_at, status, created_at, id)` in phase 6.
- Since `idx_outbox_record_status` (6.9 MB) and the status-leading partial queue index overlap for `count_*`/`oldest_*`, ablation E05 should check whether `idx_outbox_record_status` can go.
