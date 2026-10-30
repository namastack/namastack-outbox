# H16: One per-key index for G1 head probe and G2 bulk load (and its portable twin)
Status: measured
Target queries: `findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey` (G2) + G1 predecessor probe (`NOT EXISTS` / head-of-key, H04-H06, H09 form).

## Change
Index states (`H16/indexes-*.sql`), each also drops the unused `record_key_created`:
- **U1 (PG recommendation candidate):** `(record_key, created_at, id) WHERE status IN ('NEW','FAILED')` (= H15a). G2: `status = 'NEW'` (literal), H14 or IN form. G1: head-of-key probe written with `status IN ('NEW','FAILED')` (H09 form) - needs the H09 predicate; if H09 is rejected, U1' = same with `WHERE completed_at IS NULL` (= H02/H12 index) and `completed_at IS NULL` in both queries.
- **U2a (portable, zero DDL):** existing `(record_key, completed_at, created_at)` with `completed_at IS NULL` (+ `status = 'NEW'` filter for G2), G1 probe as H06. Incremental sort on `id` inside equal `created_at` only.
- **U2b (portable):** `(record_key, completed_at, created_at, id)` replacing rkcc and rkc; fully ordered probes, fewer sorts, size ~ +40 MB vs rkcc.
- Reference points: H10 (`(record_key, status, created_at, id)`, G2 only; the G1 head needs two ranges for NEW+FAILED so it cannot serve it).
G2 queries for the matrix: `H16/queries/` (= H14c LATERAL), `H14/queries*` and `H15/queries*` per state; G1 probes: `experiments/H06/queries` (U2), the H09 query set (U1).

## Rationale / expected plan
G1's head probe needs "any incomplete (NEW or FAILED) row of this key before (created_at, id)" = first entry of the key's incomplete set in `(created_at, id)` order; G2 needs all NEW of the key in that order. A per-key
index over the union set (NEW+FAILED) in `(created_at, id)` order serves both: G1 reads the first entry, G2 reads the range and filters FAILED (few). Predicate choice:
`status IN (...)` (U1) avoids the status x completed_at misestimate that drives G2 plan flips and the G1 D filter-off regression (H05); the `completed_at IS NULL` form needs H05's workarounds. Non-partial dialects use `completed_at IS NULL` as the equality prefix (NULL is an index key in PG, MySQL, MariaDB, SQL Server, Oracle composite indexes where another column is non-null), so one index serves both there too.
Expected: U1 = H15 numbers for G2 (+ H14 for stability) and one small index for G1: write cost lower than H10 (partial; entry removed on completion) - target insert WAL <= +3% total with the drop; U2a = 0 write cost, G2 B 100 ~1.2 ms, B1000 depends on H14.

## Semantic equivalence argument
Index-only change plus the query forms of H14/H15/H09/H12 (each carries its own argument). Invariants used: NEW/FAILED <=> `completed_at IS NULL` (U1 with status form: only the reverse direction needed for G1; U2/H12: NEW => `completed_at IS NULL`). Not schema-enforced; add a note for the ADR.

## Portability
postgres: U1 or U1'. sqlserver: filtered index U1 (IN allowed) or U2b. mysql/mariadb/oracle: U2b (or U2a), no partial index; JPA/Mongo: entity index annotations list the non-partial U2b / `{recordKey, completed_at?, createdAt, _id}` equivalent (Mongo `status`-prefixed variant is H10's).

## Experiment plan
Run after the G1 iter-2 results (H06/H08/H09) are in. For each state U1, U1', U2a, U2b (fresh states from base, post-seed in place): G2 matrix A, B, D, E x keys 10/100/1000 x custom/generic (IN form and H14 LATERAL form), G1 probe set (key query on/off, 64p/256p), spill check, write cost (fresh container, pre-seed DDL: insert/update ms per 1k, WAL B/op, index pages). Choose by: G1 + G2 hot path latency, write cost <= +15% total with H08's other changes, one index only.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before -> after
Pending.
## Equivalence check: PASS/FAIL + method
Pending.
## Regression: other queries whose plan changed; write cost; index size
Pending.
## Decision + reasoning
Pending.

## Experimenter note (iteration 2, Sonnet experimenter; drivers `G2-iter2/`, raw `results/G2i2-*`, `results/G2i2-wc-A/`)
States measured on top of H08a: U2a = H08a (zero DDL, index `record_key_completed_created`), U2b = `(record_key, completed_at, created_at, id)` replacing rkcc, H10 = `(record_key, status, created_at, id)` added next to rkcc. U1/U1' (status/completed_at partial) not measured (H15 deprioritised). Full B/D/A tables and plans: see H14 section "Experimenter note".

Best bulk query per state, median ms custom (generic) at 100 / 1000 keys; baseline B 6.5 / 22.5, D 40* / 40*; A 0.86 / 4.2:

| state | query | A 100 / 1000 | B 100 / 1000 | D 100 / 1000 | spill D |
|---|---|---|---|---|---|
| U2a (H08a) | original IN | 0.95 / 4.3 (11.4) | 6.5 / 22.7 (22.4) | 40.8* / 41.2* | yes |
| U2a | LATERAL Lon (no top ORDER BY, `completed_at IS NULL`, ORDER BY completed_at, created_at, id) | 0.51 / 4.8 (5.4) | 0.76 / 7.9 (8.6) | 22 / 22 (21.5) | no |
| U2a | LATERAL Lo (top ORDER BY) | 0.71 / 6.6 (11.7) | 1.23 / 11.6 (17.5) | 38 / 38 (38) | no |
| **U2b** | **Lon** | 0.39 / 4.0 (4.4) | **0.63 / 7.1 (6.9)** | **8.9 / 8.8 (8.9)** | no |
| U2b | Lo | 0.69 / 6.0 (7.0) | 1.28 / 10.6 (13.9) | 26.5 / 26.8 (27.4) | no |
| U2b | original IN | 0.89 / 4.0 (11.6) | 6.7 / 22.6 (22.5) | 40.7* / 40.3* | yes |
| H10 (+rkcc) | original IN | 0.28 / 4.2 (3.9) | 1.33 / 15.0 (5.6) | 40.7* / 40.6* | yes |
| H10 | LATERAL Ln (status = 'NEW', no top ORDER BY) | 0.41 / 3.9 (4.3) | 0.59 / 6.3 (6.9) | 8.7 / 8.4 (8.4) | no |
| H10 | LATERAL L (top ORDER BY) | 0.62 / 6.1 (6.0) | 1.00 / 11.0 (10.9) | 26.9 / 27.1 (27.1) | no |

Conclusions: (1) with no new index (U2a) the LATERAL form Lon meets B -50% at 100 (-88%) and 1000 (-65%) keys, generic included (-61%), and removes the D spill (D 40 -> 22 ms, status stays a filter over each key's 1000 incomplete-or-new rows); a top ORDER BY costs an extra Incremental Sort and loses the generic 1000-key target. (2) U2b adds the final step: `id` in the index gives a pure per-key ordered Index Scan, D 8.8 ms (-78%), B 1000 7.1 (-68%), generic = custom. (3) H10 matches U2b for the bulk query (Ln B 6.3, D 8.4) but is a G2-only index and, with rkcc kept for G1, a second 17-18k page index. (4) Without LATERAL no index state meets the target at 1000 keys (H10 original B 15.0 -22.5%, D spills).

### Write cost (A, 50k inserts then 50k updates; `results/G2i2-wc-A/`)
| set | insert ms/1k, WAL B/op | update ms/1k, WAL B/op | rkcc-index pages after cycle | total index pages after |
|---|---|---|---|---|
| H08a (re-measured) | 26.6, 2,488 | 34.7, 3,178 | 9,404 | 36,539 |
| U2b | 25.7, 2,566 (+3.1%) | 33.8, 3,309 (+4.1%) | 18,230 (+94%, +~70 MB at 1M rows: 95 vs 71 MB) | 45,368 (+24%) |
| H10 added to H08a | 27.6, 2,665 (+7.1%) | 35.4, 3,452 (+8.6%) | 9,451 + 18,192 | 54,600 approx |
(ms/1k within +-3% noise; WAL +-1%.) U2b vs the original baseline (2,438 / 3,318 B): insert +5.2%, update -0.3%.

### Equivalence and regression
Equivalence: see H14 (all states incl. U2b and H10, 0 FAIL in A-E and edge). G1 key queries under U2b keep the H08a plans (anti join with `incomplete_created` and the key index; Limit early stop), custom 0.1-0.4 ms, generic 0.3-1.2 ms unchanged; the known filter-off scalar form improves strongly under U2b (D 64p 395 -> 14.5 ms, B 218 -> 177). Portability: U2b is non-partial and identical on all dialects; LATERAL is PG/MySQL-specific (see H14).

### Recommendation (no decision taken)
Smallest step: keep H08a and switch the PG bulk query to Lon (zero DDL, B -65%, no D spill). Optional step: U2b (+3-4% write WAL, +24% index pages) for D 22 -> 8.8 ms and generic = custom. Original IN-query remains the portable fallback (MariaDB, JPA, Mongo).


## Orchestrator decision
U2a accepted; U2b optional → Phase 2. decide U2b in Phase 2 against write budget (ledger: `../STATE.md`; final report: `../99-final-report.md`)
