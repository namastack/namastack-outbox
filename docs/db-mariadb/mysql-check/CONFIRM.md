# MySQL confirm: `keys_*` head form vs `NOT EXISTS` (2026-11-08)

Follows `REPORT.md` (screen). Spec `confirm-spec.md`, driver `confirm-run.sh`, results `results-confirm/{pass1,pass2,bp2g,equiv}`, tables from `confirm-report.py`.

## Setup
- MySQL `mysql:9.2`, harness lane 1 (port 13406), default settings (binlog on, 128 MiB pool), solo; code/catalog `mdb-opt2` = `4037faa5`, branch MySQL DDL. UUIDv7 (`HARNESS_ID_VERSION=7` on every step; snapshot marker `ids=uuidv7` checked before and after each cell; `ids.txt` per tree).
- `my-head` = branch (head form); `my-ne` = overlays `forms-ne/` (`keys_strict.sql`, `keys_nonstrict.sql` = `NOT EXISTS`, only these two).
- Pass 1: head, ne, labels forward; container stop/start; pass 2: ne, head, labels reversed; fresh gen at the start of each pass (the snapshot is reused for both runs of a label). Bench groups `HOT,WRITE,MON`, 5+20 reps, per-query limit max(2x other run's median, 60 s) (`--limit-from`), no `pages` key file (own `keys_strict` keys). S10 cells: `concurrency` runs on MySQL unchanged and uses the overlaid `keys_strict` (verified in `Extra.kt`: `Catalog.load(..., cm.queries)`); no harness change needed. `PURGE BINARY LOGS BEFORE NOW()` before and after every cell; free disk never below 31 GB.
- Host load 1.0-3.8 (no outside load); thresholds HOT 11 % (SPREAD>2x = same run differing > 22 % between passes; none occurred for `keys_*`).
- 2 GB pass: container recreated with `--innodb-buffer-pool-size=2G`; the server reports 2 415 919 104 B (2304 MiB; rounded up to 6 instances x 128 MiB chunks), so the driver accepts 2-2.5 GiB (`results-confirm/failures.txt` holds the first strict-equality abort, fixed and resumed). Default (134 217 728) restored, container stopped, volume kept.

## Equivalence (my-ne vs my-head, v7, `HARNESS_SAMPLE_ORDERED=1`, before pass 1)
S1 28 PASS; S2, S5, S7 26 PASS + 2 SKIP (unbounded finds); 0 WARN/FAIL. `keys_strict`/`keys_nonstrict` return the same keys in the same order. Files `results-confirm/equiv/mysql/*/my-head-vs-my-ne.md`.

## Timings (median ms; ne/head < 1: NOT EXISTS faster; partial ownership first)

### keys_strict (median ms)

| label | head p1 | ne p1 | ne/head p1 | head p2 | ne p2 | ne/head p2 | verdict p1 / p2 | SPREAD>2x head / ne |
|---|---|---|---|---|---|---|---|---|
| S8-S2 | 2.99 | 1.83 | 0.61 | 3.08 | 1.79 | 0.58 | ne better / ne better | no / no |
| S8-S4 | 0.90 | 0.93 | 1.04 | 0.90 | 0.92 | 1.02 | = / = | no / no |
| S16-S4 | 0.73 | 0.76 | 1.05 | 0.73 | 0.78 | 1.07 | = / = | no / no |
| S8-S5 | 31619 | 227 | 0.01 | 31791 | 228 | 0.01 | ne better / ne better | no / no |
| S8-S6 | 1.04 | 1.09 | 1.04 | 1.08 | 1.08 | 1.01 | = / = | no / no |
| S8-S7 | 20.66 | 0.88 | 0.04 | 20.57 | 0.88 | 0.04 | ne better / ne better | no / no |
| S16-S2 | 1.12 | 0.91 | 0.81 | 1.13 | 0.91 | 0.80 | ne better / ne better | no / no |
| S1 | 1.92 | 1.39 | 0.72 | 1.95 | 1.36 | 0.70 | ne better / ne better | no / no |
| S2 | 1.04 | 1.09 | 1.05 | 1.09 | 1.11 | 1.02 | = / = | no / no |
| S4 | 1.15 | 1.19 | 1.04 | 1.11 | 1.18 | 1.06 | = / = | no / no |
| S4-b100 | 1.70 | 1.70 | 1.00 | 1.71 | 1.73 | 1.01 | = / = | no / no |
| S5 | 14.03 | 8.51 | 0.61 | 13.95 | 8.46 | 0.61 | ne better / ne better | no / no |
| S6 | 1.21 | 1.28 | 1.05 | 1.21 | 1.25 | 1.04 | = / = | no / no |
| S7 | 1.07 | 1.08 | 1.01 | 1.08 | 1.11 | 1.03 | = / = | no / no |

### keys_nonstrict (median ms)

| label | head p1 | ne p1 | ne/head p1 | head p2 | ne p2 | ne/head p2 | verdict p1 / p2 | SPREAD>2x head / ne |
|---|---|---|---|---|---|---|---|---|
| S8-S2 | 3.56 | 1.79 | 0.50 | 3.50 | 1.81 | 0.52 | ne better / ne better | no / no |
| S8-S4 | 0.91 | 0.92 | 1.02 | 0.90 | 0.94 | 1.05 | = / = | no / no |
| S16-S4 | 0.73 | 0.78 | 1.08 | 0.73 | 0.76 | 1.05 | = / = | no / no |
| S8-S5 | 1795 | 995 | 0.55 | 1607 | 961 | 0.60 | ne better / ne better | no / no |
| S8-S6 | 1.04 | 1.10 | 1.06 | 1.06 | 1.09 | 1.02 | = / = | no / no |
| S8-S7 | 25.98 | 0.88 | 0.03 | 26.02 | 0.88 | 0.03 | ne better / ne better | no / no |
| S16-S2 | 1.19 | 0.87 | 0.73 | 1.20 | 0.86 | 0.72 | ne better / ne better | no / no |
| S1 | 2.06 | 1.26 | 0.61 | 2.01 | 1.22 | 0.61 | ne better / ne better | no / no |
| S2 | 0.99 | 1.01 | 1.02 | 0.98 | 1.01 | 1.03 | = / = | no / no |
| S4 | 1.04 | 1.07 | 1.02 | 1.04 | 1.07 | 1.04 | = / = | no / no |
| S4-b100 | 1.90 | 1.84 | 0.97 | 1.88 | 1.83 | 0.97 | = / = | no / no |
| S5 | 910 | 977 | 1.07 | 1001 | 986 | 0.99 | = / = | no / no |
| S6 | 1.15 | 1.17 | 1.01 | 1.11 | 1.16 | 1.05 | = / = | no / no |
| S7 | 0.97 | 1.02 | 1.05 | 0.97 | 1.00 | 1.03 | = / = | no / no |

Other queries (oldest_*, pages, counts) use identical statements in both runs; `--all` tables in `confirm-report.py` output show them within noise of each other.
### 2 GB buffer pool (2304 MiB reported; median ms)

| label | query | head | ne | ne/head | verdict |
|---|---|---|---|---|---|
| S8-S2 | keys_strict | 2.81 | 1.67 | 0.60 | ne better |
| S8-S2 | keys_nonstrict | 3.33 | 1.69 | 0.51 | ne better |
| S8-S5 | keys_strict | 32080 | 229 | 0.01 | ne better |
| S8-S5 | keys_nonstrict | 101 | 54.11 | 0.54 | ne better |
| S5 | keys_strict | 14.29 | 8.54 | 0.60 | ne better |
| S5 | keys_nonstrict | 59.08 | 52.59 | 0.89 | ne better |

### S10 (60 s)

| cell | run | processed/s | inserted/s | keys_query_ms | pages_ms | errors |
|---|---|---|---|---|---|---|
| pass1 S10-S2-p1-unthr | my-head | 250.7 | 1212.6 | n=1511 p50=0.86 p95=1.09 | n=1511 p50=0.35 p95=0.60 | 0 |
| pass1 S10-S2-p1-unthr | my-ne | 250.8 | 1209.6 | n=1512 p50=0.80 p95=1.01 | n=1512 p50=0.36 p95=0.59 | 0 |
| pass1 S10-S2-p4-unthr | my-head | 748.7 | 948.6 | n=4512 p50=5.10 p95=21.60 | n=4512 p50=0.38 p95=0.55 | 0 |
| pass1 S10-S2-p4-unthr | my-ne | 894.6 | 1081.1 | n=5392 p50=3.77 p95=10.50 | n=5392 p50=0.38 p95=0.54 | 0 |
| pass1 S10-S4-p4 | my-head | 836.0 | 271.1 | n=5038 p50=3.15 p95=6.22 | n=5038 p50=0.57 p95=2.21 | 0 |
| pass1 S10-S4-p4 | my-ne | 781.8 | 255.9 | n=4711 p50=3.12 p95=7.73 | n=4711 p50=0.57 p95=2.29 | 0 |
| pass1 S10-S4-p4-unthr | my-head | 687.0 | 877.1 | n=4141 p50=3.41 p95=6.79 | n=4141 p50=0.57 p95=2.40 | 0 |
| pass1 S10-S4-p4-unthr | my-ne | 689.2 | 879.1 | n=4154 p50=3.41 p95=7.00 | n=4154 p50=0.58 p95=2.36 | 0 |
| pass2 S10-S2-p1-unthr | my-head | 204.2 | 980.5 | n=1230 p50=0.86 p95=1.10 | n=1230 p50=0.36 p95=0.60 | 0 |
| pass2 S10-S2-p1-unthr | my-ne | 253.6 | 1217.5 | n=1528 p50=0.80 p95=1.03 | n=1528 p50=0.35 p95=0.58 | 0 |
| pass2 S10-S2-p4-unthr | my-head | 817.3 | 1020.8 | n=4925 p50=4.04 p95=20.61 | n=4925 p50=0.38 p95=0.56 | 0 |
| pass2 S10-S2-p4-unthr | my-ne | 893.3 | 1078.7 | n=5385 p50=3.78 p95=10.57 | n=5385 p50=0.38 p95=0.55 | 0 |
| pass2 S10-S4-p4 | my-head | 793.6 | 258.0 | n=4784 p50=3.12 p95=6.67 | n=4784 p50=0.58 p95=2.24 | 0 |
| pass2 S10-S4-p4 | my-ne | 786.8 | 258.2 | n=4742 p50=3.11 p95=7.04 | n=4742 p50=0.57 p95=2.25 | 0 |
| pass2 S10-S4-p4-unthr | my-head | 651.1 | 837.4 | n=3926 p50=3.33 p95=7.05 | n=3926 p50=0.60 p95=2.44 | 0 |
| pass2 S10-S4-p4-unthr | my-ne | 643.9 | 825.3 | n=3883 p50=3.28 p95=6.37 | n=3883 p50=0.59 p95=2.29 | 0 |
| bp2g S10-S2-p4-unthr | my-head | 916.4 | 1108.1 | n=5523 p50=1.46 p95=16.91 | n=5523 p50=0.38 p95=0.55 | 0 |
| bp2g S10-S2-p4-unthr | my-ne | 925.3 | 1089.0 | n=5577 p50=2.87 p95=8.75 | n=5577 p50=0.38 p95=0.55 | 0 |

S10 notes: `keys_strict` is the only changed statement. S2 p4 unthrottled: ne processed/s 894 / 893 vs head 749 / 817 (+9 to +19 %), keys p95 10.5 vs 21 ms (head p50 4.0-5.1 vs ne 3.8); S2 p1 unthrottled equal in pass 1 (251 vs 251), pass 2 head 204 vs ne 254 (head's pass-2 run is 19 % below its pass 1, a SPREAD flag for throughput); S4 p4 and p4-unthr equal within 5 %. 2 GB S2 p4 unthr: 916 vs 925 processed/s, keys p50 1.46 vs 2.87 ms (head faster p50, ne better p95: 16.9 vs 8.8 ms), net equal.

## Plans (EXPLAIN ANALYZE, `results-confirm/pass1/*/mysql/<label>/plans/`, rows from `handlers.csv`)
- Head form: outer scan, then a dependent `Select #2` per outer row: `ORDER BY e.created_at, e.id LIMIT 1` over `idx_outbox_record_key_completed_created_id (record_key, completed_at = NULL)` is a **ref lookup + Sort**, so each lookup reads all unfinished rows of the key. S8-S5 `keys_nonstrict`: 100 ms/loop x 18 loops reading 2000 rows per key (1.8 s); `keys_strict`: 31.6 s (1 execution). The strict head predicate `o.id = (subselect)` also lets the optimizer pick `idx_outbox_record_status_partition` and filter all candidates before the sort: S8-S7 `keys_strict` examines 2510 outer rows and 2574 key reads (20.7 ms), S8-S7 `keys_nonstrict` 26 ms.
- `NOT EXISTS`: antijoin over `idx_outbox_record_status_created_id` in `(created_at, id)` order with early stop; the probe stops at the first earlier incomplete row. S8-S7 `keys_strict`: 30 outer rows scanned, 11 key reads, 0.88 ms. S8-S5 `keys_strict`: 34 k key reads / 218 k next reads, 227 ms (outer rows are mostly blocked by an earlier row, so ~200 k outer rows are visited); `keys_nonstrict`: still reads ~1100 rows per key probed (55 ms/loop) so 1.0 s vs head 1.6-1.8 s. S8-S2/S1/S16-S2: ne sorts 229 candidates and stops after 10 (1.8 vs 3.0 ms) while head evaluates the subselect for all 229 (571 key reads vs 352).
- Where equal (S8-S4, S16-S4, S4, S4-b100, S6, S7, S2): both stop within a few outer rows; the difference is below noise.

## Verdict (ADR-0005 rule; P1 first)
`keys_strict` / `keys_nonstrict` are P1 reads. `NOT EXISTS` is better in both passes and equal elsewhere: S8-S5 `keys_strict` 31.6 s -> 227 ms (139x), `keys_nonstrict` 1.6-1.8 s -> 0.96-1.0 s; S8-S7 21 / 26 ms -> 0.88 ms; S8-S2 / S1 / S16-S2 20-50 % faster; S5 `keys_strict` 14.0 -> 8.5 ms; `keys_nonstrict` S5 equal. At 2 GB the same ordering holds (S8-S5 `keys_nonstrict` 101 -> 54 ms). Head form is never better beyond noise on any label (S2 p1/p4 S10 within noise). No write cost (no schema change). **Verdict: win for `NOT EXISTS` on MySQL 9.2** (replicated in both passes, no SPREAD>2x on `keys_*`).

## Recommendation for `MySqlJdbcOutboxRecordQueries`
Use `NOT EXISTS` (the `DefaultJdbcOutboxRecordQueries` form) for `keys_strict` and `keys_nonstrict` on MySQL; keep the head form for MariaDB (a mixed design: MariaDB overrides with the head form, MySQL falls back to default). Caveats: DDL without `completed_at` in the status indexes, 128 MiB pool (large-scenario absolute numbers contain pool misses, 2 GB pass confirms the ranking), MySQL 9.2 only (8.x not measured). S4/S5 `keys_nonstrict` ~1 s remains for both forms (scans ~2000 rows per hot key).

## Deviations and harness changes
- No harness source changes (MySQL support and `DIALECT` exist from the screen). Driver fixes while running: container-not-ready start (restart after the first attempt, results discarded), results path per pass (cells already finished moved into `pass1/`), 2 GB check made a range because of chunk rounding.
- `--limit-from` for the first run of a label uses the screen's `my-B` timings if present; no query hit a limit except none (`>` flags absent in the tables).
- A stray first S10 head cell (p1-unthr, pass 1) ran before the path fix and was moved intact into `pass1/`.
