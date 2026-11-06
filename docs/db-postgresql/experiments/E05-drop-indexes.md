# E05 — drop-index ablations on v1b (A1, A2, A5, A6, K12), PostgreSQL

Analysis of existing round 2 measurements only. Spec: `experiments/round2-spec.md`; reference: `experiments/R2-v1b.md`. Paths are relative to `docs/db-postgresql/`. User rule (STATE.md): an index is justified only by P1/P2 queries and only if its read benefit outweighs its write cost; P3 is reported, never decisive. **Partial partition ownership (`S8-*`, S10 with 4 pollers) is the default deployment and is listed first; S5, S6, S7 were measured with full ownership only.**

## Hypotheses, base, changes

All variants start from v1b (`experiments/R2-v1b/ddl.sql`, `experiments/R2-v1b/queries/pages.sql`); each differs only in the DDL given (`experiments/R2-<variant>/ddl.sql`):

| Variant | Change vs v1b | Query overrides | Question |
|---|---|---|---|
| A1 | drop `idx_outbox_record_status (status)` (6.7 MiB) | none | pure write overhead? (`(status)` is a prefix of `(status, next_retry_at)`) |
| A2 | drop `idx_outbox_record_status_retry (status, next_retry_at)` (38.3 MiB) | none | serves only P3? |
| A5 | drop `idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at)` (38.4 MiB) | none | `count_partitions` / `oldest_due_*` via the partial queue index? |
| A6 | drop all three | literal `AND completed_at IS NULL` added to `count_new`, `count_failed`, `count_statuses`, `count_partitions`, `count_partition_single`, `oldest_due_nonstrict`, `find_failed_before`, `ids_failed_before`; `pages` = v1b form (`experiments/R2-A6/queries/`) | can the single partial queue index serve P1 + P2? |
| K12 | record-key index `(record_key, completed_at, created_at)` without `id` (117.8 -> 73.8 MiB) | none | does the `id` column justify 44 MiB? |

No branch/code change. Equivalence vs v1 (PASS 26, SKIP 2, no WARN/FAIL) on S2, S5, S7 for A1, A2, A5, A6, K12: `results/equiv/postgres/{S2,S5,S7}/<variant>-vs-v1.md`.

## Method and reading rules

Cell = median ms from `results/<variant>/postgres/<label>-R2/timings.csv` plus plan mode from `plancache.csv` in the same directory (`C` all custom, `G` all generic, `M` 4 custom + 16 generic). Deltas vs v1b are printed only when they exceed the group threshold (HOT/WRITE 30 %, MON/ADMIN/RET 20 %) **and** 0.15 ms (**bold**) or when the plan-mode mix differs from v1b (`?`, the cell is suspect, plan mode may explain it). `xN` = ratio. Each cell is **one run**. Repeats exist only for S10 (S10 + S10b) and write cost (S2-wc2, v1 and v1b only); A1, A2, A5, K12 have no repeat at all. Verdicts below therefore rely on effects far larger than the noise estimates, and say so when not.

## Index-to-query map (from plans, P1/P2 only)

Index names are shortened (`idx_outbox_record_` omitted). Source: `results/<variant>/postgres/<label>-R2/plans/<query>.json` (`EXPLAIN (ANALYZE, BUFFERS)` with the first bind set, custom plan). Writes (`insert`, `upd_*`, `delete_by_id`) use the primary key only; the secondary indexes are maintained, not read.

| Query | Prio | v1b, S8-S2 / S8-S4 / S2 / S5 | Fallback when its index is dropped |
|---|---|---|---|
| keys_strict | P1 | `status_created_id_incomplete` + `record_key_completed_created_id` (predecessor anti join), all labels | unchanged in A1/A2/A5/A6/K12 (`results/*/postgres/S8-S2-R2/plans/keys_strict.json`) |
| keys_nonstrict | P1 | `status_created_id_incomplete` (S8-S2, S2); + `record_key_completed_created_id` (S4, S5, S7, S8-S4) | unchanged |
| pages | P1 | `status_created_id_incomplete` (S8-S2, S2); `record_key_completed_created_id` (S4, S7, S8-S4); + `status` bitmap (S5) | A5 S8-S2: switches to `record_key_completed_created_id` |
| count_statuses | P2 | `status` (index-only, all labels) | A1: `status_retry`; A6: `status_created_id_incomplete` |
| count_partitions | P2 | `partition_status_retry` (S8-S2, S8-S4, S4-S7); `status` (S2) | A5: Parallel Index Scan on `status` with `partition_no` filter (S8-S4: 215 ms, 249 728 rows removed per worker); A6: queue index |
| oldest_created | P2 | `status_created_id_incomplete` | unchanged (A1, A2, A5, A6) |
| oldest_due_strict | P2 | `status_retry` + `record_key_completed_created_id` (predecessor) | A2: Parallel Index Scan on `status` + sort (S8-S4 plan 247 ms); A6: Parallel Seq Scan + external sort (S8-S4 plan 250 ms) |
| oldest_due_nonstrict | P2 | `status_retry` | A2: `partition_status_retry` (S8-S4 plan 27.5 ms); A6: queue index |
| count_new / count_failed / count_partition_single / find_incomplete_by_key | P3 | `status`, `partition_status_retry`, `record_key_completed_created_id` | n/a |

Plan excerpts: `results/v1b/postgres/S8-S4-R2/plans/oldest_due_strict.txt` (Index Scan `status_retry`, `Index Cond: status = 'NEW' AND next_retry_at <= ...`, 10 buffers, 0.082 ms) vs `results/A2/postgres/S8-S4-R2/plans/oldest_due_strict.txt` (Parallel Index Scan on `idx_outbox_record_status`, then Sort, 112 350 reads, 247 ms) vs `results/A6/postgres/S8-S4-R2/plans/oldest_due_strict.txt` (Parallel Seq Scan + `Sort Method: external merge`, 250 ms). The partial queue index carries `next_retry_at` only as an `INCLUDE` column, so it cannot serve `ORDER BY next_retry_at LIMIT 1`; this is why A6 cannot absorb `oldest_due_strict`. A5: `results/v1b/postgres/S8-S4-R2/plans/count_partitions.txt` (Index Only Scan `partition_status_retry`, 1 718 buffers, 27 ms) vs `results/A5/postgres/S8-S4-R2/plans/count_partitions.txt` (Parallel Index Scan on `status`, 215 ms). Plans are single captures; production statements run generic plans after 5 executions (`plancache.csv`), which may choose differently.

## Verdict (ranked P1, then P2; partial ownership first)

| Index | Dropped in | P1 effect (S8-S2, S8-S4, S10, then S2/S4/S5/S6/S7) | P2 effect (read loss) | Write gain | Verdict |
|---|---|---|---|---|---|
| `idx_outbox_record_status` (6.7 MiB) | A1 | none (keys, pages within noise in all 7 labels; S5 pages +17 %, n.s.) | count_statuses +37 % S8-S2 (1.33 -> 1.83 ms), +27 % S8-S4 (38.3 -> 48.9 ms); S2: count_statuses +36 %, count_partitions +38 % (1.07 vs 0.77 ms; plan moves from `status` to `status_retry`). S8-S2 count_partitions +50 % (0.469 -> 0.702 ms) and oldest_due_strict +38 % have an unchanged plan (`partition_status_retry` / `status_retry`), so they are noise | WAL/write -1.1 % / -1.4 % / -1.7 % (insert 1 thr / insert 4 thr / upd_complete), within or just above the 0-1.2 % repeat noise; -6.7 MiB (-2.4 % index volume) | **marginal, not proven**: no P1 effect, P2 loss is +0.5 ms for count_statuses (S8-S2), write gain at noise level. Dropping is acceptable but yields nothing measurable; needs a repeat. |
| `idx_outbox_record_status_retry` (38.3 MiB) | A2 | none | oldest_due_strict S8-S2 0.608 -> 1.749 ms (+188 %), S8-S4 0.707 -> 527 ms (x746), S4 0.987 -> 803 ms, S5 0.931 -> 143 ms, S6 1.21 -> 109 ms, S7 0.845 -> 9.2 ms; oldest_due_nonstrict S8-S4 0.254 -> 16.8 ms (x66, same mode `M`), S4 0.435 -> 65.7 ms | WAL/write -0.4 % / -1.0 % / -1.7 % (noise level) despite -38.3 MiB (-13.9 %) | **keep**: serves P2 `oldest_due_*` (not P3-only); drop shows no measurable write gain. |
| `idx_outbox_record_partition_status_retry` (38.4 MiB) | A5 | none attributable (pages S8-S2 -34 % `?`, S7 +39 % 0.644 -> 0.898 ms: unexplained single-run cells) | count_partitions +39 % S8-S2 (0.469 -> 0.651 ms), x12.7 S8-S4 (16.5 -> 209 ms), S4 64.7 -> 211.9, S5 13.7 -> 62.4, S6 21.0 -> 84.7, S7 1.94 -> 3.70 ms; S2 n.s. (v1b uses `status` there) | WAL/write -6.6 % / -6.2 % / -6.7 %, clearly above the 0-1.2 % noise; -38.4 MiB; latency p50 -11 % (1 thr), +0.2 % (4 thr), not distinguishable from 0-9 % noise | **mixed**: the only single drop with a measurable write gain, paid by `count_partitions` (P2, once per metrics refresh): +0.18 ms on 1 M rows with 1 200 NEW (S8-S2), +190 ms with about 250 000 NEW rows in the owned partitions (S8-S4). Decide by metrics refresh rate vs write rate; not decided by this data. `count_partition_single` (P3) also loses (x379 S8-S4). |
| all three | A6 | none (keys/pages/writes within noise; S10 below) | oldest_due_strict S8-S2 0.608 -> 258.7 ms, S8-S4 0.707 -> 442.5 ms (Seq Scan); count_statuses S8-S2 +43 %, S8-S4 38.3 -> 121.2 ms (x3.2); count_partitions S8-S4 16.5 -> 122.5 ms; oldest_due_nonstrict S8-S4 0.254 -> 144.6 ms (`?`) | WAL/write -9.1 % / -8.7 % / -9.3 %; -83.5 MiB (-30.2 %); S9 churn 265 s vs 407 s (-35 %, single run) | **not adoptable as is**: P1 is cost-free, but `oldest_due_strict` (P2) becomes a table scan (0.6 -> 259-722 ms); adding the literal `completed_at IS NULL` does not help it. |
| record-key `id` column (44 MiB) | K12 | none (all P1 cells within noise; S4 write cells see below) | none material (oldest_due_strict S7 +36 %, 0.845 -> 1.150 ms; oldest_created S5 +43 %, 0.687 -> 0.985 ms, both single-run, no pattern in other labels) | WAL/write -1.1 % / -0.9 % / -2.4 % (noise level); -44.0 MiB (-15.9 % of index volume, -37 % of this index) | **win on space, no read or write-latency loss detected**; the write gain is at the noise limit. Candidate to adopt (`id` only breaks ties in `pages` ordering, a final `Incremental Sort` of 10 rows, 0.08 ms in the K12 S8-S4 plan). Verify tie ordering in `pages` before adopting. |

Ranking by what drop is worth taking: K12 (space, no loss) > A5 (largest measurable write gain, P2 count_partitions loss) > A1 (no measurable gain) > A2 (loss, no gain) > A6 (large P2 loss). A1 and A5 together (keep only `idx_status_retry`, queue index, record-key, PK) were **not measured**; additive WAL estimate -7.7 % is a hypothesis, not a result.

## Results: partial ownership (S8-S2, S8-S4), P1 then P2

**S8-S2** (`results/<variant>/postgres/S8-S2-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 1.078C | 1.038C | 0.946C | 0.950C | 0.940C | 0.922C |
| keys_nonstrict | HOT | 1.445C | 0.997C **-31%** | 1.346C | 0.970C **-33%** | 1.248C | 1.369C |
| pages | HOT | 1.042C | 0.773M -26% ? | 1.005C | 0.688M **-34%** ? | 0.930C | 0.935C |
| insert | WRITE | 0.368M | 0.354M | 0.374M | 0.355M | 0.322M | 0.343M |
| upd_complete | WRITE | 0.355M | 0.327M | 0.321M | 0.300M | 0.343M | 0.361M |
| upd_retry | WRITE | 0.320G | 0.255G | 0.261G | 0.290G | 0.299G | 0.280G |
| upd_fail | WRITE | 0.286G | 0.272G | 0.232G | 0.254G | 0.233G | 0.220G |
| delete_by_id | WRITE | 0.187M | 0.144M | 0.195M | 0.184M | 0.179M | 0.148M |
| count_statuses | MON | 1.329C | 1.826C **+37%** | 1.244C | 1.520C | 1.894C **+43%** | 1.330C |
| count_partitions | MON | 0.469C | 0.702C **+50%** | 0.491C | 0.651C **+39%** | 0.476C | 0.520C |
| oldest_created | MON | 0.361C | 0.500C | 0.402C | 0.469C | 0.333C | 0.385C |
| oldest_due_strict | MON | 0.608C | 0.838C **+38%** | 1.749C **+188%** | 0.824C **+36%** | 258.737C **x426** | 0.680C |
| oldest_due_nonstrict | MON | 0.170M | 0.289M | 0.689C **+305%** ? | 0.261M | 0.674C **+296%** ? | 0.220M |

**S8-S4** (`results/<variant>/postgres/S8-S4-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 0.931C | 0.978C | 1.105C | 0.972C | 0.896C | 0.954C |
| keys_nonstrict | HOT | 1.000C | 0.971C | 0.960C | 0.979C | 1.023C | 0.985C |
| pages | HOT | 0.844M | 0.665M | 0.659M | 0.753M | 0.686M | 0.878M |
| insert | WRITE | 0.532M | 0.507M | 0.548M | 0.523M | 0.425M | 0.475M |
| upd_complete | WRITE | 0.371M | 0.300M | 0.346M | 0.328M | 0.339M | 0.405M |
| upd_retry | WRITE | 0.333G | 0.249G | 0.349G | 0.291G | 0.283G | 0.316G |
| upd_fail | WRITE | 0.339G | 0.233G | 0.202G | 0.209G | 0.237G | 0.252G |
| delete_by_id | WRITE | 0.134M | 0.201M | 0.142M | 0.173M | 0.181M | 0.149M |
| count_statuses | MON | 38.331C | 48.863C **+27%** | 38.366C | 38.314C | 121.224C **+216%** | 39.038C |
| count_partitions | MON | 16.522C | 16.346M -1% ? | 15.687M -5% ? | 209.255C **x12.7** | 122.507C **+641%** | 16.227C |
| oldest_created | MON | 0.412C | 0.315C | 0.348C | 0.376C | 0.380C | 0.436C |
| oldest_due_strict | MON | 0.707C | 0.631C | 527.300C **x746** | 0.679C | 442.514C **x626** | 0.688C |
| oldest_due_nonstrict | MON | 0.254M | 0.181M | 16.825M **x66.2** | 0.239M | 144.638C **x569** ? | 0.274M |

S8-S4 `count_failed` is mode `G` (21.7-22.2 ms) in A1, A2, A5, A6 and v1 but `C` (0.25 / 0.21 ms) in v1b and K12 on identical SQL; it is the round 1 bimodal cell and not an index effect. Cells marked `?` in P2 (S8-S4 `count_partitions`, `oldest_due_nonstrict`, `count_new`) differ in plan mode from v1b and are suspect; `count_partitions` A1/A2/K12 vs v1b is `M` vs `C` with identical timings (16.3, 15.7, 16.2 vs 16.5 ms).

Reading S8-S2 (the default deployment, 1 M rows): P1 reads do not depend on any of the five drops; keys_nonstrict A1 -31 % and A5 -33 % (bold) and pages A5 -34 % are not reproduced in S2/S8-S4 and v1b's own keys_nonstrict S8-S2 (1.445 ms) is above its S2/S8-S4 values (1.087/1.000), so these are v1b-side noise, not index effects. S8-S4 (backlog, about 250 000 NEW in owned partitions) shows the P2 losses clearly: `count_partitions` 16.5 ms -> 209 ms (A5), 122.5 ms (A6); `oldest_due_strict` 0.707 ms -> 527 ms (A2), 442.5 ms (A6); `count_statuses` 38.3 ms -> 121.2 ms (A6).

P3 (below, not decisive): `count_partition_single` x379 (A5), x296 (A6) in S8-S4; `find_failed_before`/`ids_failed_before` slower in A2 (+144 %, +137 %, 0.27 -> 0.66 ms) and A6 (S8-S2); `find/ids_completed_before` 44-51 % faster in A1 and A6 in S8-S2 and 24-26 % in S8-S4 (A1 ids only, A6 both), cause unknown (no index is meant for them; single run).

P3 (reported only):

**S8-S2** (`results/<variant>/postgres/S8-S2-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| count_new | MON | 0.440C | 0.371C | 0.351C | 0.487C | 0.406C | 0.352C |
| count_failed | MON | 0.805C | 1.128C **+40%** | 0.805C | 0.771C | 1.379C **+71%** | 0.771C |
| count_partition_single | MON | 0.172C | 0.283C | 0.228C | 0.434C **+152%** | 0.303C | 0.155C |
| count_completed | MON | 29.842C | 63.591C **+113%** | 29.932C | 29.840C | 106.364M **+256%** ? | 29.177C |
| find_incomplete_by_key | ADMIN | 0.313C | 0.138M **-56%** ? | 0.285C | 0.185M -41% ? | 0.153M **-51%** ? | 0.378C |
| delete_by_key_status | RET | 0.267C | 0.138M -48% ? | 0.259C | 0.116M **-57%** ? | 0.098M **-63%** ? | 0.212C |
| find_completed_before | RET | 510.787M | 250.660C **-51%** ? | 508.575M | 504.751M | 247.823C **-51%** ? | 512.328M |
| ids_completed_before | RET | 505.616M | 251.335M **-50%** | 507.039M | 497.378M | 280.938M **-44%** | 501.542M |
| find_failed_before | RET | 0.269C | 0.262C | 0.656C **+144%** | 0.250C | 5.371C **x20** | 0.285C |
| ids_failed_before | RET | 0.228C | 0.183C | 0.541C **+137%** | 0.178C | 1.289C **+465%** | 0.189C |
| delete_by_status_failed | RET | 12.159C | 11.574C | 11.828C | 11.482C | 198.761M **x16.3** ? | 11.783C |
| delete_by_ids | RET | 4.596C | 4.481C | 4.502C | 4.511C | 4.494C | 4.727C |

**S8-S4** (`results/<variant>/postgres/S8-S4-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| count_new | MON | 29.553C | 40.763M **+38%** ? | 29.290M -1% ? | 29.541M -0% ? | 158.228M **+435%** ? | 29.247C |
| count_failed | MON | 0.252C | 21.944G **x87.1** ? | 21.798G **x86.5** ? | 21.909G **x86.9** ? | 22.226G **x88.2** ? | 0.214C |
| count_partition_single | MON | 0.533M | 0.431M | 0.438M | 201.812M **x379** | 157.507M **x296** | 0.590C +11% ? |
| count_completed | MON | 29.645C | 42.029G **+42%** ? | 29.489G -1% ? | 29.511G -0% ? | 189.194M **+538%** ? | 29.655C |
| find_incomplete_by_key | ADMIN | 0.165M | 0.146M | 0.153M | 0.202M | 0.118M | 0.156M |
| delete_by_key_status | RET | 0.122M | 0.107M | 0.105M | 0.099M | 0.100M | 0.099M |
| find_completed_before | RET | 509.001C | 471.856C | 494.921C | 494.918C | 388.637C **-24%** | 499.064C |
| ids_completed_before | RET | 512.092C | 381.203C **-26%** | 485.527C | 487.125C | 379.249C **-26%** | 491.029C |
| find_failed_before | RET | 0.272C | 0.247C | 0.221C | 0.300C | 0.292C | 0.241C |
| ids_failed_before | RET | 0.235C | 0.272C | 0.156C | 0.206C | 0.221C | 0.255C |
| delete_by_status_failed | RET | 0.169C | 0.158C | 0.130C | 0.136C | 229.895M **x1.36e+03** ? | 0.144C |
| delete_by_ids | RET | 5.158C | 4.820C | 4.889C | 4.775C | 4.937C | 4.994C |

## Results: S10 concurrency (only v1b and A6 have S10 runs; two passes each, second pass in reverse variant order)

Files: `results/{v1b,A6}/postgres/S10-R2/concurrency-p{1,4}-r0.txt` (pass 1) and `.../S10b-R2/...` (pass 2). 60 s, unthrottled producers.

| pollers | variant | processed rec/s pass 1 | pass 2 | pass spread | keys p50 ms (1 / 2) | pages p50 ms (1 / 2) | update p50 ms (1 / 2) |
|---|---|---:|---:|---:|---|---|---|
| 4 | v1b | 1055 | 1052 | -0% | 2.11 / 1.75 | 14.18 / 14.33 | 1.51 / 1.56 |
| 4 | A6 | 904 | 1209 | +34% | 2.09 / 4.96 | 16.31 / 1.03 | 1.69 / 2.29 |
| 1 | v1b | 334 | 237 | -29% | 3.93 / 2.13 | 0.69 / 14.24 | 1.83 / 1.64 |
| 1 | A6 | 422 | 227 | -46% | 3.4 / 1.95 | 0.59 / 16.92 | 1.6 / 1.68 |

Pass-to-pass spread is -0 % (v1b, 4 pollers) to +34 % (A6, 4 pollers) and -29 % / -46 % at 1 poller, so **no S10 throughput difference between v1b and A6 is established** (4 pollers: mean 1 054 vs 1 057 rec/s; 1 poller: mean 285 vs 325). The `pages` p50 flips between ~0.6-1.0 ms and 14-17 ms within the same variant (v1b 3 of 4 runs, A6 2 of 4 runs at 14-17 ms), independent of the dropped indexes; cause unverified (no plans during S10). A1, A2, A5, K12 were not run in S10.

## Results: full ownership (S2, S4, S5, S6, S7; S5/S6/S7 measured with full ownership only)

**S2** (`results/<variant>/postgres/S2-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 1.349C | 1.224C | 1.198C | 1.222C | 1.113C | 1.214C |
| keys_nonstrict | HOT | 1.087C | 1.195C | 1.119C | 1.156C | 1.136C | 1.212C |
| pages | HOT | 0.638M | 0.705M | 0.935C **+47%** ? | 0.991C **+55%** ? | 0.673M | 0.976C **+53%** ? |
| insert | WRITE | 0.359M | 0.349M | 0.338M | 0.280M | 0.311M | 0.341M |
| upd_complete | WRITE | 0.271M | 0.357M | 0.327M | 0.318M | 0.309M | 0.405M |
| upd_retry | WRITE | 0.299G | 0.288G | 0.214G | 0.259G | 0.260G | 0.246G |
| upd_fail | WRITE | 0.299G | 0.262G | 0.180G | 0.202G | 0.226G | 0.253G |
| delete_by_id | WRITE | 0.148M | 0.201M | 0.149M | 0.171M | 0.148M | 0.149M |
| count_statuses | MON | 1.207C | 1.638C **+36%** | 1.273C | 1.285C | 1.837C **+52%** | 1.302C |
| count_partitions | MON | 0.774C | 1.067C **+38%** | 0.816C | 0.836C | 0.662C | 0.790C |
| oldest_created | MON | 0.642C | 0.640C | 0.635C | 0.619C | 0.493C | 0.538C |
| oldest_due_strict | MON | 0.884C | 0.862C | 3.233C **+266%** | 0.839C | 190.991C **x216** | 0.861C |
| oldest_due_nonstrict | MON | 0.380M | 0.375M | 1.246C **+228%** ? | 0.329M | 1.069C **+181%** ? | 0.307M |

**S4** (`results/<variant>/postgres/S4-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 1.251C | 1.397C | 1.322C | 1.236C | 1.212C | 1.264C |
| keys_nonstrict | HOT | 1.222C | 1.166C | 1.209C | 1.216C | 1.163C | 1.208C |
| pages | HOT | 0.739M | 0.743M | 0.856M | 0.696M | 0.740M | 0.738M |
| insert | WRITE | 0.461M | 0.534M | 0.524M | 0.438M | 0.595M | 0.868M **+88%** |
| upd_complete | WRITE | 0.334M | 0.313M | 0.326M | 0.369M | 0.741M **+122%** | 0.997M **+199%** |
| upd_retry | WRITE | 0.273G | 0.279G | 0.296G | 0.277G | 0.268G | 0.450G **+65%** |
| upd_fail | WRITE | 0.259G | 0.254G | 0.258G | 0.262G | 0.216G | 0.235G |
| delete_by_id | WRITE | 0.149M | 0.158M | 0.182M | 0.195M | 0.180M | 0.188M |
| count_statuses | MON | 38.440C | 49.177C **+28%** | 38.512C | 38.835C | 119.162C **+210%** | 38.657C |
| count_partitions | MON | 64.723C | 62.824M -3% ? | 63.437C | 211.891C **+227%** | 119.141C **+84%** | 64.260M -1% ? |
| oldest_created | MON | 0.752C | 0.573C **-24%** | 0.575C **-24%** | 0.626C | 0.555C **-26%** | 0.718C |
| oldest_due_strict | MON | 0.987C | 0.873C | 803.031C **x814** | 0.896C | 722.032C **x732** | 1.018C |
| oldest_due_nonstrict | MON | 0.435M | 0.357M | 65.744M **x151** | 0.427M | 151.791C **x349** ? | 0.503M |

**S5** (`results/<variant>/postgres/S5-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 1.353C | 1.264C | 1.307C | 1.316C | 1.239C | 1.267C |
| keys_nonstrict | HOT | 1.239C | 1.211C | 1.206C | 1.216C | 1.266C | 1.236C |
| pages | HOT | 94.709C | 110.614M +17% ? | 102.753M +8% ? | 97.343M +3% ? | 95.283M +1% ? | 96.256M +2% ? |
| insert | WRITE | 0.344M | 0.325M | 0.313M | 0.333M | 0.312M | 0.337M |
| upd_complete | WRITE | 0.392M | 0.354M | 0.361M | 0.270M | 0.314M | 0.346M |
| upd_retry | WRITE | 0.298G | 0.285G | 0.237G | 0.251G | 0.254G | 0.234G |
| upd_fail | WRITE | 0.193G | 0.242G | 0.190G | 0.218G | 0.210G | 0.197G |
| delete_by_id | WRITE | 0.168M | 0.166M | 0.136M | 0.161M | 0.134M | 0.154M |
| count_statuses | MON | 18.740C | 16.079C | 18.877C | 18.703C | 21.590C | 18.589C |
| count_partitions | MON | 13.664C | 13.658C | 13.468C | 62.394C **+357%** | 21.425C **+57%** | 13.521C |
| oldest_created | MON | 0.687C | 0.663C | 0.614C | 0.950C **+38%** | 0.716C | 0.985C **+43%** |
| oldest_due_strict | MON | 0.931C | 0.967C | 143.443C **x154** | 1.045C | 149.638C **x161** | 1.086C |
| oldest_due_nonstrict | MON | 0.362M | 0.352M | 13.923M **x38.5** | 0.434M | 23.376C **x64.6** ? | 0.423M |

**S6** (`results/<variant>/postgres/S6-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 1.214C | 1.189C | 1.287C | 1.190C | 1.192C | 1.311C |
| keys_nonstrict | HOT | 1.210C | 1.122C | 1.227C | 1.126C | 1.143C | 1.208C |
| pages | HOT | 0.734M | 0.599M | 0.705M | 0.642M | 0.883M | 0.679M |
| insert | WRITE | 0.402M | 0.427M | 0.365M | 0.331M | 0.337M | 0.441M |
| upd_complete | WRITE | 0.363M | 0.358M | 0.405M | 0.367M | 0.324M | 0.365M |
| upd_retry | WRITE | 0.264G | 0.268G | 0.285G | 0.234G | 0.231G | 0.286G |
| upd_fail | WRITE | 0.255G | 0.282G | 0.253G | 0.196G | 0.238G | 0.246G |
| delete_by_id | WRITE | 0.188M | 0.196M | 0.144M | 0.141M | 0.194M | 0.176M |
| count_statuses | MON | 21.548C | 25.878C **+20%** | 21.485C | 21.688C | 21.818C | 21.475C |
| count_partitions | MON | 20.962C | 21.171C | 20.720C | 84.722C **+304%** | 21.358C | 20.560C |
| oldest_created | MON | 0.657C | 0.566C | 0.662C | 0.717C | 0.578C | 0.731C |
| oldest_due_strict | MON | 1.212C | 0.881C **-27%** | 109.192C **x90.1** | 0.904C **-25%** | 192.896C **x159** | 0.994C |
| oldest_due_nonstrict | MON | 0.424M | 0.249M **-41%** | 1.992C **+370%** ? | 0.408M | 16.658C **x39.3** ? | 0.459M |

**S7** (`results/<variant>/postgres/S7-R2/timings.csv`, `plancache.csv`; delta vs v1b; `?` = plan-mode mix differs from v1b)

| query | grp | v1b | A1 | A2 | A5 | A6 | K12 |
|---|---|---:|---:|---:|---:|---:|---:|
| keys_strict | HOT | 1.428C | 1.201C | 1.187C | 1.252C | 1.140C | 1.164C |
| keys_nonstrict | HOT | 1.085C | 1.193C | 1.150C | 1.237C | 1.055C | 1.151C |
| pages | HOT | 0.644M | 0.807M | 0.633M | 0.898M **+39%** | 0.674M | 0.740M |
| insert | WRITE | 0.394M | 0.392M | 0.360M | 0.426M | 0.309M | 0.302M |
| upd_complete | WRITE | 0.345M | 0.346M | 0.297M | 0.348M | 0.355M | 0.367M |
| upd_retry | WRITE | 0.300G | 0.285G | 0.271G | 0.281G | 0.283G | 0.291G |
| upd_fail | WRITE | 0.268G | 0.195G | 0.245G | 0.257G | 0.247G | 0.214G |
| delete_by_id | WRITE | 0.196M | 0.138M | 0.165M | 0.199M | 0.178M | 0.166M |
| count_statuses | MON | 26.764C | 29.194C | 26.947C | 26.771C | 38.063C **+42%** | 26.916C |
| count_partitions | MON | 1.943C | 1.981C | 2.010C | 3.700C **+90%** | 1.729C | 1.857C |
| oldest_created | MON | 0.583C | 0.672C | 0.530C | 0.544C | 0.610C | 0.590C |
| oldest_due_strict | MON | 0.845C | 0.913C | 9.185C **+987%** | 0.789C | 201.675C **x239** | 1.150C **+36%** |
| oldest_due_nonstrict | MON | 0.337M | 0.408M | 2.509C **+645%** ? | 0.257M | 2.086C **+519%** ? | 0.384M |

Notes: (1) S2 `pages` is `M` (0.64 ms) in v1b and A1/A6 but `C` (0.94-0.99 ms, +47 % to +55 %) in A2, A5, K12; the three `C` cells are not related to what these variants drop (K12 changes another index, A2/A5 drop indexes `pages` does not use there), so this is the plan-mode flip, not an index effect. (2) S4 write cells: A6 `upd_complete` 0.741 ms (+122 %), K12 `insert` 0.868 (+88 %), `upd_complete` 0.997 (+199 %), `upd_retry` 0.450 (+65 %). These do not reproduce on identical data in S8-S4 (K12 0.475/0.405, A6 0.425/0.339 ms) or in S2/S5/S6/S7, and the S4 variant order started with A6 then K12 (`results/logs/round2-pg.log`, label S4), so a warm-up/order artefact is the likely explanation; not proven. (3) S5/S6/S7 `count_*` cells with `?` are plan-mode suspect (`count_new` A1 S5 `M` vs `C`).

## Write cost (S2, committed single-row writes) for every DDL variant

Files: `results/<variant>/postgres/S2-R2/writecost.csv` (single pass for A1, A2, A5, A6, K12), repeat `results/{v1b}/postgres/S2-wc2-R2/writecost.csv` (v1b only; shown as `v1b(2)`), per-index sizes in the `index_bytes_*` lines. Deltas vs v1b pass 1. Repeat noise of the same variant (v1b pass 1 vs 2, and v1 in `R2-v1b.md`): WAL/write 0.2-1.2 %, p50 latency 0.3-8.7 %, throughput 2-8 %. Write phases run once per variant in a fixed order (main, v1, v1b, A1, A2, A5, A6, K12), not rotated.

| metric | v1b | v1b(2) | A1 | A2 | A5 | A6 | K12 |
|---|---:|---:|---:|---:|---:|---:|---:|
| insert 1 thread p50 ms | 2.06 | 2.04 (-0.7%) | 1.57 (-23.9%) | 1.96 (-4.8%) | 1.83 (-10.9%) | 1.52 (-26.1%) | 1.96 (-5.0%) |
| insert 1 thread WAL B/write | 6731 | 6696 (-0.5%) | 6660 (-1.1%) | 6707 (-0.4%) | 6284 (-6.6%) | 6117 (-9.1%) | 6656 (-1.1%) |
| insert 4 threads p50 ms | 1.86 | 1.85 (-0.6%) | 1.83 (-1.8%) | 1.85 (-0.8%) | 1.86 (+0.2%) | 1.78 (-4.6%) | 1.85 (-0.6%) |
| insert 4 threads rec/s | 1813 | 1771 (-2.3%) | 1724 (-4.9%) | 1776 (-2.1%) | 1801 (-0.6%) | 1852 (+2.2%) | 1788 (-1.4%) |
| insert 4 threads WAL B/write | 6848 | 6862 (+0.2%) | 6750 (-1.4%) | 6781 (-1.0%) | 6424 (-6.2%) | 6254 (-8.7%) | 6784 (-0.9%) |
| upd_complete p50 ms | 2.14 | 2.13 (-0.3%) | 1.73 (-18.8%) | 2.03 (-5.1%) | 1.90 (-11.2%) | 1.95 (-8.8%) | 2.04 (-4.3%) |
| upd_complete rec/s | 468 | 457 (-2.3%) | 548 (+17.1%) | 483 (+3.1%) | 524 (+11.9%) | 522 (+11.6%) | 474 (+1.2%) |
| upd_complete WAL B/write | 7552 | 7507 (-0.6%) | 7426 (-1.7%) | 7427 (-1.7%) | 7049 (-6.7%) | 6850 (-9.3%) | 7374 (-2.4%) |
| indexes incl. PK (count) | 6 | 6 | 5 | 5 | 5 | 3 | 6 |
| indexes total before, MB | 276.5 | 276.5 (+0.0%) | 269.8 (-2.4%) | 238.2 (-13.9%) | 238.1 (-13.9%) | 193.0 (-30.2%) | 232.4 (-15.9%) |
| indexes total after 15k writes, MB | 281.4 | 281.5 (+0.0%) | 274.5 (-2.4%) | 243.0 (-13.6%) | 243.0 (-13.7%) | 197.6 (-29.8%) | 236.5 (-15.9%) |

Reading: WAL/write is the reliable signal. A5 (-6.6 %, -6.2 %, -6.7 %) and A6 (-9.1 %, -8.7 %, -9.3 %) are clearly outside the 0-1.2 % noise; A1, A2, K12 are -0.4 % to -2.4 % (at or just beyond the noise). Dropping `idx_status_retry` (A2, 38 MiB) saves almost nothing in WAL while dropping `idx_partition_status_retry` (A5, 38 MiB) saves 6.6 %; a possible reason is that `status`-leading entries for new/updated rows land on the same right-edge pages, while `partition_no`-leading entries scatter across the tree and cause more page writes/full-page images. This is a hypothesis and was not tested (e.g. with `pg_waldump`). Latency p50 differences (A1 -24 % / -19 %, A6 -26 % / -9 % at 1 thread; 4 threads -2 % / -5 %) exceed the repeat noise at one thread but do not follow the number or size of the dropped indexes (A2/A5 drop 38 MiB each: -5 %, -11 %), so they are not attributed to the indexes; single runs.

Index counts include the primary key. Sizes (MiB, before the writes) of dropped indexes: `idx_status` 6.7, `idx_status_retry` 38.3, `idx_partition_status_retry` 38.4, record-key 117.8 (K12: 73.8), from the `index_bytes_before.*` lines of `results/{v1b,K12}/postgres/S2-R2/writecost.csv`.

## S9 churn (A6 vs v1b, one run each, 500 000 inserts, no manual VACUUM)

`results/{v1b,A6}/postgres/S9-R2/churn.txt`: A6 265 s vs v1b 407 s (-35 %); table after churn 1 612 MB vs 1 735 MB; total index size after churn 427 MB vs 543 MB (record-key 229 vs 216 MB, queue partial 74 vs 78 MB, PK 124 vs 119 MB); dead tuples 214 429 vs 134 305. The run time difference is large, but it is one run per variant with different autovacuum timing (A6's last autovacuum 09:17-09:18 vs v1b 09:11); treat as indicative. No other variant has S9.

## Dialect portability

Only PostgreSQL measured. `idx_status` and the two `status_retry` indexes are plain B-trees on every dialect, so the drop/keep decisions transfer qualitatively, but the P2 fallbacks differ: H2/MySQL/MariaDB have no partial queue index (v1 uses a wide composite on `completed_at`) and the Oracle/SQL Server planners were not checked. The `completed_at IS NULL` literal used by A6 is portable SQL. K12 (dropping `id` from the record-key index) is dialect-neutral; on clustered-PK engines (InnoDB, SQL Server) the secondary index carries the PK anyway, so the saving there would be smaller (not measured).

## Follow-ups

- Measure A1 + A5 together (keep only `idx_status_retry` of the three), with repeats, in S8-S2/S8-S4/S10 and write cost; and decide on `count_partitions` with a different SQL form (e.g. a partition-indexed count or `status`-leading scan), because A5's only cost is that P2 query.
- Give `oldest_due_strict` a way to use the partial queue index (e.g. a partial `(next_retry_at)` or `(status, next_retry_at, partition_no)` partial index) before revisiting A6; the E04 / MIN report (`experiments/E04-partial-record-key.md`) covers the partial record-key index and the three-index drop.
- Repeat write cost for A1, A2, A5, K12 (rotated order) and `pg_waldump` the A2 vs A5 WAL difference.
- S5/S6/S7 with partial ownership, S10 for A1/A2/A5/K12.
