# R2-v1b — v1b vs v1 vs main (PostgreSQL, round 2)

Analysis of existing measurements only (no new runs). Spec: `experiments/round2-spec.md`. All paths below are relative to `docs/db-postgresql/`.

## Hypothesis, base, change

v1b is the new reference for rounds 2+: v1 with (a) the incomplete-record index replaced by the E02b index (status-leading, partial) and (b) `pages` in the base `IN (...)` form instead of `unnest` + `LATERAL`. Round 2 re-measures main, v1, v1b back to back on one snapshot per scenario to confirm the round 1 verdicts (`experiments/E02-status-created-v1.md`, `experiments/E1P-pages-default-pg.md`) under one reference.

```sql
-- v1:  CREATE INDEX idx_outbox_record_created_id_incomplete ON outbox_record (created_at, id)
--        INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL;
-- v1b: CREATE INDEX idx_outbox_record_status_created_id_incomplete ON outbox_record (status, created_at, id)
--        INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL;   (experiments/R2-v1b/ddl.sql)
-- pages (v1b): SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status
--        AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id        (experiments/R2-v1b/queries/pages.sql)
```
No branch, no code change (DDL + query override only). Equivalence v1b vs v1: PASS 26, SKIP 2, no WARN/FAIL on S2, S5, S7 (`results/equiv/postgres/S2/v1b-vs-v1.md`, `.../S5/v1b-vs-v1.md`, `.../S7/v1b-vs-v1.md`).

## How to read the tables

- Cell = median ms (`results/<variant>/postgres/<label>-R2/timings.csv`) followed by the plan mode of the timed reps from `plancache.csv` in the same directory: `C` all 20 reps custom plan, `G` all generic, `M` 4 custom + 16 generic (pgjdbc switches to a server-prepared generic plan after 5 executions). `†` = fewer than 20 reps (slow-query cap).
- Delta columns: relative change of the right-named variant against the left-named one. **Bold** = exceeds the STATE.md group threshold (HOT/WRITE 30 %, MON/ADMIN/RET 20 %) **and** 0.15 ms absolute. `?` = the plan-mode mix differs between the two compared variants, so the cell is suspect (plan mode, not the variant, may explain it). `-100%` means a change of at least 99.5 %; `xN` is a ratio.
- Priorities: P1 = keys_strict, keys_nonstrict, pages, writes (insert, upd_*, delete_by_id), S10; P2 = count_statuses, count_partitions, oldest_*. count_new/count_failed/count_partition_single/count_completed (deprecated metrics module) and retention/admin are P3: reported, never decisive.
- Every latency cell is **one run** (20 reps, one `prepare`). The only repeated measurements are S10 (S10 + S10b) and `writecost` (S2 + S2-wc2). Single cells flipping by > 30 % between labels with identical code paths (see "Plan-mode caveat") mean that individual cells near threshold are not reliable.
- Partial partition ownership (`S8-*`, `--owned mod4`) is the default deployment and is listed first. **S5, S6, S7 were measured with full ownership only**; no partial-ownership counterpart exists for them.

## Verdict

**v1b is the better reference than v1; confirm.** Evidence by priority:

1. **P1 reads, partial ownership (S8-S2, S8-S4):** keys_strict S8-S2 1.078 ms vs v1 1.967 (-45 %, bold) and main 2.182 (-51 %); S8-S4 0.931 vs v1 0.987 (-6 %, within noise) and main 2 870 ms (M, 20 reps; min 1 763 ms). keys_nonstrict S8-S4 1.000 vs v1 0.986 (no difference); S8-S2 1.445 vs v1 1.967 (-27 %, below threshold) but **vs main 0.991 v1b is +46 % (bold)**: this single cell is worse than main and than v1b's own S2 value (1.087); it was not repeated. `pages` v1b vs v1: no difference above threshold (S8-S2 1.042 vs 0.829 `?`, +26 %; S8-S4 0.844 vs 0.704, +20 %; both below 30 %).
2. **P1 writes (all labels, S10):** no write cell exceeds threshold between v1b and v1 (see tables). S10 throughput: no difference distinguishable from the 2-pass noise (below).
3. **P2 (S8-S2/S8-S4):** oldest_created v1b 0.361 vs v1 1.168 ms in S8-S2 (-69 %, bold), 0.412 vs 0.367 in S8-S4 (n.s.); count_partitions S8-S2 -28 % (0.469 vs 0.653 ms) uses the same index (`idx_outbox_record_partition_status_retry`) and the same plan mode in v1 and v1b, so it is not attributed to v1b (noise); oldest_due_* and count_statuses no difference.
4. **Full ownership:** keys_strict/keys_nonstrict S2 -38 % / -48 % vs v1 (bold; 1.349/1.087 vs 2.161/2.106 ms); S7 -89 % / -92 % (1.428/1.085 vs 13.225/12.906 ms) — the `status`-leading index removes the walk over FAILED entries that v1's `created_at`-leading index does; S4/S5/S6 no difference between v1 and v1b (1.2-1.5 ms). pages S5 94.7 vs 135.8 ms (-30 %, bold, mix `C` vs `C`, same as round 1 E1P).
5. **vs main:** keys_*, oldest_* collapse from 0.5-14.5 s (S6 0.475 s, S4 4.3 s, S5 14.5 s, S8-S4 2.9 s) to ~1 ms in the backlog scenarios, same as v1. The only P1 read where v1b is not better than main is keys_nonstrict in S8-S2 (+46 %, see above, one run) and S4/S8-S4 plan-mode-suspect cells.
6. **Write cost** (below): v1 and v1b are indistinguishable (WAL/write within the measured 0.5-1.3 % repeat noise, latency within 1-8 %). Both write 1-5 % more WAL per insert than main (see table) and carry 18 MiB less index volume than main (276.5 vs 294.7 MiB; main has two record-key indexes).
7. **Churn S9** (one run each): v1b 407 s vs v1 423 s, total index size after churn 543 vs 603 MiB, queue index after churn 78 vs 94 MiB.

Not a win everywhere: P1 keys_nonstrict S8-S2 vs main (+46 %, unrepeated), `pages` S10 (below) and P3 deltas (below). No verdict on the `pages` `IN`-form beyond "no detected difference at SQL level, but S10 shows a plan-mode/latency anomaly".

## Results, partial ownership first

### S8-S2 and S8-S4 (partial ownership, mod4) — P1 then P2

**S8-S2** (`results/<variant>/postgres/S8-S2-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 2.182C | 1.967C | 1.078C | **-45%** | **-51%** |
| keys_nonstrict | HOT | 0.991C | 1.967C | 1.445C | -27% | **+46%** |
| pages | HOT | 0.937C | 0.829M | 1.042C | +26% ? | +11% |
| insert | WRITE | 0.411M | 0.340M | 0.368M | +8% | -10% |
| upd_complete | WRITE | 0.375M | 0.322M | 0.355M | +10% | -5% |
| upd_retry | WRITE | 0.299G | 0.320G | 0.320G | +0% | +7% |
| upd_fail | WRITE | 0.257G | 0.205G | 0.286G | +40% | +11% |
| delete_by_id | WRITE | 0.154M | 0.140M | 0.187M | +34% | +21% |
| count_statuses | MON | n/a | 1.226C | 1.329C | +8% | n/a |
| count_partitions | MON | 0.497C | 0.653C | 0.469C | **-28%** | -6% |
| oldest_created | MON | 0.712C | 1.168C | 0.361C | **-69%** | **-49%** |
| oldest_due_strict | MON | 1.547C | 0.675C | 0.608C | -10% | **-61%** |
| oldest_due_nonstrict | MON | 0.246M | 0.253M | 0.170M | -33% | -31% |

**S8-S4** (`results/<variant>/postgres/S8-S4-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 2870.046M | 0.987C | 0.931C | -6% | **-100%** ? |
| keys_nonstrict | HOT | 1450.758M | 0.986C | 1.000C | +1% | **-100%** ? |
| pages | HOT | 0.706M | 0.704M | 0.844M | +20% | +20% |
| insert | WRITE | 0.537M | 0.554M | 0.532M | -4% | -1% |
| upd_complete | WRITE | 0.388M | 0.276M | 0.371M | +34% | -4% |
| upd_retry | WRITE | 0.288G | 0.284G | 0.333G | +17% | +16% |
| upd_fail | WRITE | 0.274G | 0.251G | 0.339G | +35% | +24% |
| delete_by_id | WRITE | 0.171M | 0.133M | 0.134M | +1% | -22% |
| count_statuses | MON | n/a | 38.717C | 38.331C | -1% | n/a |
| count_partitions | MON | 16.363M | 16.054M | 16.522C | +3% ? | +1% ? |
| oldest_created | MON | 210.972C | 0.367C | 0.412C | +12% | **-100%** |
| oldest_due_strict | MON | 852.475M | 0.687C | 0.707C | +3% | **-100%** ? |
| oldest_due_nonstrict | MON | 0.293M | 0.238M | 0.254M | +7% | -13% |

S8-S4 main keys_strict/keys_nonstrict are mode `M` (2 870 / 1 451 ms medians; min 1 763 / 664 ms), i.e. main itself is bimodal there; the `?` on the `-100%` cells reflects `M` (main) vs `C` (v1b). The direction (main seconds, v1b ~1 ms) matches S4 (`results/main/postgres/S4-R2/timings.csv`, `C`).

P3 (reported, not decisive):

**S8-S2** (`results/<variant>/postgres/S8-S2-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| count_new | MON | 0.386C | 0.414C | 0.440C | +6% | +14% |
| count_failed | MON | 0.991C | 0.793C | 0.805C | +2% | -19% |
| count_partition_single | MON | 0.223C | 0.160C | 0.172C | +7% | -23% |
| count_completed | MON | 29.087C | 28.803C | 29.842C | +4% | +3% |
| find_incomplete_by_key | ADMIN | 0.157M | 0.328C | 0.313C | -5% | **+99%** ? |
| delete_by_key_status | RET | 0.127M | 0.238C | 0.267C | +12% | +110% ? |
| find_completed_before | RET | 1540.513M | 505.714M | 510.787M | +1% | **-67%** |
| ids_completed_before | RET | 652.485M | 497.246M | 505.616M | +2% | **-23%** |
| find_failed_before | RET | 0.294C | 0.278C | 0.269C | -3% | -9% |
| ids_failed_before | RET | 0.147C | 0.203C | 0.228C | +12% | +55% |
| delete_by_status_failed | RET | 10.824C | 11.483C | 12.159C | +6% | +12% |
| delete_by_ids | RET | 4.480C | 4.428C | 4.596C | +4% | +3% |

**S8-S4** (`results/<variant>/postgres/S8-S4-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| count_new | MON | 29.829M | 29.661M | 29.553C | -0% ? | -1% ? |
| count_failed | MON | 21.837G | 21.745G | 0.252C | **-99%** ? | **-99%** ? |
| count_partition_single | MON | 0.558M | 0.507M | 0.533M | +5% | -4% |
| count_completed | MON | 30.042G | 29.491G | 29.645C | +1% ? | -1% ? |
| find_incomplete_by_key | ADMIN | 0.155M | 0.154M | 0.165M | +7% | +6% |
| delete_by_key_status | RET | 0.133M | 0.133M | 0.122M | -8% | -8% |
| find_completed_before | RET | 1571.379M | 504.822C | 509.001C | +1% | **-68%** ? |
| ids_completed_before | RET | 652.889M | 495.655C | 512.092C | +3% | **-22%** ? |
| find_failed_before | RET | 0.244C | 0.277C | 0.272C | -2% | +11% |
| ids_failed_before | RET | 0.208C | 0.256C | 0.235C | -8% | +13% |
| delete_by_status_failed | RET | 0.186C | 0.162C | 0.169C | +4% | -9% |
| delete_by_ids | RET | 4.623C | 4.856C | 5.158C | +6% | +12% |

S8-S4 count_failed: main/v1 are mode `G` (21.7-21.8 ms, generic plan on `status = $1`), v1b mode `C` (0.252 ms). Same SQL in all three; this is the round 1 bimodal cell, not a v1b effect. See `experiments/R2-plan-modes.md`.

### S10 concurrency, 4 pollers (partial ownership by construction: poller i owns partitions p % 4 == i) and 1 poller

Files: `results/{v1,v1b}/postgres/S10-R2/concurrency-p{1,4}-r0.txt` (pass 1), `.../S10b-R2/...` (pass 2, reverse variant order). 60 s, unthrottled producers (4 threads), rate 0.

| pollers | variant | processed rec/s pass 1 | pass 2 | spread | keys p50 ms (1 / 2) | pages p50 ms (1 / 2) | update p50 ms (1 / 2) | inserted rec/s (1 / 2) |
|---|---|---:|---:|---:|---|---|---|---|
| 4 | v1 | 1296 | 1025 | -21% | 5.64 / 11.9 | 0.57 / 1.31 | 1.7 / 2.54 | 1861 / 1638 |
| 4 | v1b | 1055 | 1052 | -0% | 2.11 / 1.75 | 14.18 / 14.33 | 1.51 / 1.56 | 2145 / 2110 |
| 1 | v1 | 336 | 316 | -6% | 6.07 / 6.36 | 0.63 / 0.67 | 1.67 / 1.76 | 1946 / 1838 |
| 1 | v1b | 334 | 237 | -29% | 3.93 / 2.13 | 0.69 / 14.24 | 1.83 / 1.64 | 1747 / 2133 |

Reading: pass-to-pass spread of the same variant is -21 % / 0 % (v1 / v1b, 4 pollers) and -6 % / -29 % (1 poller); A6 in `E05` shows +34 % / -46 %. Throughput differences between v1 and v1b (1 160 vs 1 054 rec/s mean of two passes at 4 pollers, 326 vs 285 at 1 poller) are inside that noise: **no S10 throughput difference is established**. Consistent across all four v1b runs, however, is a lower keys query p50 (1.75-3.93 ms vs 5.64-11.90 ms for v1), the P1 read the new index targets. **Anomaly:** `pages` p50 is 14.2-14.3 ms in 3 of 4 v1b runs (0.69 ms in the fourth) but 0.57-1.31 ms in all four v1 runs (`unnest`/`LATERAL`); the same variant flips between 0.7 and 14 ms, so this is bimodal, not a stable v1b property. No plans are captured during S10, so the cause (e.g. plan mode of the `IN`-list statement with varying list length) is unverified. It does not show up in processed rec/s above noise.

## Results, full ownership (S2, S4, S5, S6, S7 — S5/S6/S7 were measured with full ownership only)

**S2** (`results/<variant>/postgres/S2-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 5.727C | 2.161C | 1.349C | **-38%** | **-76%** |
| keys_nonstrict | HOT | 1.350C | 2.106C | 1.087C | **-48%** | -19% |
| pages | HOT | 0.783C | 0.717M | 0.638M | -11% | -19% ? |
| insert | WRITE | 0.325M | 0.362M | 0.359M | -1% | +10% |
| upd_complete | WRITE | 0.327M | 0.321M | 0.271M | -16% | -17% |
| upd_retry | WRITE | 0.250G | 0.317G | 0.299G | -6% | +20% |
| upd_fail | WRITE | 0.237G | 0.268G | 0.299G | +12% | +26% |
| delete_by_id | WRITE | 0.185M | 0.178M | 0.148M | -17% | -20% |
| count_statuses | MON | n/a | 1.276C | 1.207C | -5% | n/a |
| count_partitions | MON | 0.809C | 0.848C | 0.774C | -9% | -4% |
| oldest_created | MON | 1.082C | 0.819C | 0.642C | **-22%** | **-41%** |
| oldest_due_strict | MON | 3.833C | 0.896C | 0.884C | -1% | **-77%** |
| oldest_due_nonstrict | MON | 0.338M | 0.407M | 0.380M | -7% | +12% |
| count_new | MON | 0.351C | 0.438C | 0.327C | -25% | -7% |
| count_failed | MON | 0.757C | 0.783C | 0.713C | -9% | -6% |
| count_partition_single | MON | 0.220C | 0.221C | 0.246C | +11% | +12% |
| count_completed | MON | 29.848C | 28.925C | 29.306C | +1% | -2% |
| find_incomplete_by_key | ADMIN | 0.152M | 0.318C | 0.179M | -44% ? | +18% |
| delete_by_key_status | RET | 0.108M | 0.277C | 0.112M | **-60%** ? | +4% |
| find_completed_before | RET | 1532.535M | 499.410M | 500.713M | +0% | **-67%** |
| ids_completed_before | RET | 652.450M | 493.191M | 496.131M | +1% | **-24%** |
| find_failed_before | RET | 0.263C | 0.304C | 0.251C | -17% | -5% |
| ids_failed_before | RET | 0.196C | 0.228C | 0.248C | +9% | +27% |
| delete_by_status_failed | RET | 11.285C | 10.853C | 11.607C | +7% | +3% |
| delete_by_ids | RET | 4.927C | 4.766C | 4.938C | +4% | +0% |

**S4** (`results/<variant>/postgres/S4-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 4272.025C† | 1.539C | 1.251C | -19% | **-100%** |
| keys_nonstrict | HOT | 5860.112M | 1.166C | 1.222C | +5% | **-100%** ? |
| pages | HOT | 0.783M | 0.686M | 0.739M | +8% | -6% |
| insert | WRITE | 0.560M | 0.478M | 0.461M | -4% | -18% |
| upd_complete | WRITE | 0.400M | 0.405M | 0.334M | -18% | -16% |
| upd_retry | WRITE | 0.291G | 0.315G | 0.273G | -13% | -6% |
| upd_fail | WRITE | 0.219G | 0.308G | 0.259G | -16% | +18% |
| delete_by_id | WRITE | 0.137M | 0.191M | 0.149M | -22% | +9% |
| count_statuses | MON | n/a | 38.410C | 38.440C | +0% | n/a |
| count_partitions | MON | 65.138M | 64.433C | 64.723C | +0% | -1% ? |
| oldest_created | MON | 215.220C | 0.677C | 0.752C | +11% | **-100%** |
| oldest_due_strict | MON | 691.256C | 0.940C | 0.987C | +5% | **-100%** |
| oldest_due_nonstrict | MON | 0.419M | 0.381M | 0.435M | +14% | +4% |
| count_new | MON | 29.091M | 29.186C | 29.412C | +1% | +1% ? |
| count_failed | MON | 21.572G | 0.286C | 0.247C | -14% | **-99%** ? |
| count_partition_single | MON | 0.560M | 0.576M | 0.604C | +5% ? | +8% ? |
| count_completed | MON | 29.387G | 29.720C | 29.677C | -0% | +1% ? |
| find_incomplete_by_key | ADMIN | 0.140M | 0.124M | 0.144M | +16% | +3% |
| delete_by_key_status | RET | 0.110M | 0.104M | 0.122M | +17% | +11% |
| find_completed_before | RET | 1584.415M | 495.409C | 497.537C | +0% | **-69%** ? |
| ids_completed_before | RET | 654.176M | 488.357C | 493.514C | +1% | **-25%** ? |
| find_failed_before | RET | 0.190C | 0.286C | 0.211C | -26% | +11% |
| ids_failed_before | RET | 0.253C | 0.255C | 0.223C | -13% | -12% |
| delete_by_status_failed | RET | 0.138C | 0.132C | 0.159C | +20% | +15% |
| delete_by_ids | RET | 4.708C | 5.196C | 4.762C | -8% | +1% |

**S5** (`results/<variant>/postgres/S5-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 14499.263C† | 1.270C | 1.353C | +7% | **-100%** |
| keys_nonstrict | HOT | 117.057C | 1.127C | 1.239C | +10% | **-99%** |
| pages | HOT | 200.788M | 135.812C | 94.709C | **-30%** | **-53%** ? |
| insert | WRITE | 0.373M | 0.290M | 0.344M | +19% | -8% |
| upd_complete | WRITE | 0.377M | 0.292M | 0.392M | +34% | +4% |
| upd_retry | WRITE | 0.289G | 0.253G | 0.298G | +18% | +3% |
| upd_fail | WRITE | 0.230G | 0.245G | 0.193G | -21% | -16% |
| delete_by_id | WRITE | 0.183M | 0.147M | 0.168M | +14% | -8% |
| count_statuses | MON | n/a | 18.851C | 18.740C | -1% | n/a |
| count_partitions | MON | 13.093M | 13.376C | 13.664C | +2% | +4% ? |
| oldest_created | MON | 63.416C | 0.718C | 0.687C | -4% | **-99%** |
| oldest_due_strict | MON | 4950.458C† | 1.006C | 0.931C | -7% | **-100%** |
| oldest_due_nonstrict | MON | 0.388M | 0.366M | 0.362M | -1% | -7% |
| count_new | MON | 7.403M | 7.519C | 7.520C | +0% | +2% ? |
| count_failed | MON | 0.197G | 0.232C | 0.293C | +26% | +49% ? |
| count_partition_single | MON | 0.431M | 0.435C | 0.431C | -1% | +0% ? |
| count_completed | MON | 10.897G | 9.091C | 9.259C | +2% | -15% ? |
| find_incomplete_by_key | ADMIN | 13.502M | 12.438C | 12.568C | +1% | -7% ? |
| delete_by_key_status | RET | 8.934M | 8.509C | 8.649C | +2% | -3% ? |
| find_completed_before | RET | 489.564M | 152.231C | 152.361C | +0% | **-69%** ? |
| ids_completed_before | RET | 180.607M | 150.810M | 150.027M | -1% | -17% |
| find_failed_before | RET | 0.278C | 0.246C | 0.317C | +29% | +14% |
| ids_failed_before | RET | 0.238C | 0.264C | 0.237C | -10% | -0% |
| delete_by_status_failed | RET | 0.197C | 0.199C | 0.196C | -2% | -1% |
| delete_by_ids | RET | 4.646C | 4.403C | 4.576C | +4% | -2% |

**S6** (`results/<variant>/postgres/S6-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 475.106C | 1.359C | 1.214C | -11% | **-100%** |
| keys_nonstrict | HOT | 109.141C | 1.249C | 1.210C | -3% | **-99%** |
| pages | HOT | 0.986M | 0.714M | 0.734M | +3% | -26% |
| insert | WRITE | 0.435M | 0.376M | 0.402M | +7% | -8% |
| upd_complete | WRITE | 0.364M | 0.410M | 0.363M | -11% | -0% |
| upd_retry | WRITE | 0.220G | 0.308G | 0.264G | -14% | +20% |
| upd_fail | WRITE | 0.216G | 0.253G | 0.255G | +1% | +18% |
| delete_by_id | WRITE | 0.136M | 0.133M | 0.188M | +41% | +38% |
| count_statuses | MON | n/a | 21.456C | 21.548C | +0% | n/a |
| count_partitions | MON | 20.862C | 20.929C | 20.962C | +0% | +0% |
| oldest_created | MON | 85.095C | 0.692C | 0.657C | -5% | **-99%** |
| oldest_due_strict | MON | 169.024C | 1.011C | 1.212C | +20% | **-99%** |
| oldest_due_nonstrict | MON | 0.383M | 0.393M | 0.424M | +8% | +11% |
| count_new | MON | 21.195C | 7.540C | 7.582C | +1% | **-64%** |
| count_failed | MON | 0.286C | 0.251C | 0.290C | +16% | +1% |
| count_partition_single | MON | 0.449C | 0.322C | 0.352C | +9% | -22% |
| count_completed | MON | 27.184C | 27.082C | 27.134C | +0% | -0% |
| find_incomplete_by_key | ADMIN | 0.194M | 0.195M | 0.177M | -9% | -9% |
| delete_by_key_status | RET | 0.152M | 0.121M | 0.104M | -14% | -32% |
| find_completed_before | RET | 1255.045M | 402.683C | 407.434C | +1% | **-68%** ? |
| ids_completed_before | RET | 479.297M | 403.607M | 405.574M | +0% | -15% |
| find_failed_before | RET | 0.316C | 0.317C | 0.236C | -26% | -25% |
| ids_failed_before | RET | 0.278C | 0.214C | 0.217C | +1% | -22% |
| delete_by_status_failed | RET | 0.171C | 0.144C | 0.173C | +20% | +1% |
| delete_by_ids | RET | 4.857C | 4.947C | 4.510C | -9% | -7% |

**S7** (`results/<variant>/postgres/S7-R2/timings.csv`)

| query | grp | main | v1 | v1b | v1b vs v1 | v1b vs main |
|---|---|---:|---:|---:|---:|---:|
| keys_strict | HOT | 55.417C | 13.225C | 1.428C | **-89%** | **-97%** |
| keys_nonstrict | HOT | 8.738C | 12.906C | 1.085C | **-92%** | **-88%** |
| pages | HOT | 1.606C | 0.854M | 0.644M | -25% | **-60%** ? |
| insert | WRITE | 0.296M | 0.413M | 0.394M | -5% | +33% |
| upd_complete | WRITE | 0.332M | 0.338M | 0.345M | +2% | +4% |
| upd_retry | WRITE | 0.264G | 0.280G | 0.300G | +7% | +14% |
| upd_fail | WRITE | 0.252G | 0.247G | 0.268G | +9% | +6% |
| delete_by_id | WRITE | 0.133M | 0.151M | 0.196M | +30% | +47% |
| count_statuses | MON | n/a | 27.048C | 26.764C | -1% | n/a |
| count_partitions | MON | 1.968C | 1.987C | 1.943C | -2% | -1% |
| oldest_created | MON | 4.230C | 1.492C | 0.583C | **-61%** | **-86%** |
| oldest_due_strict | MON | 34.942C | 0.924C | 0.845C | -9% | **-98%** |
| oldest_due_nonstrict | MON | 0.400M | 0.381M | 0.337M | -12% | -16% |
| count_new | MON | 0.713C | 0.699C | 0.692C | -1% | -3% |
| count_failed | MON | 21.850C | 21.771C | 21.987C | +1% | +1% |
| count_partition_single | MON | 0.266C | 0.272C | 0.174C | -36% | -35% |
| count_completed | MON | 24.308C | 24.034C | 24.434C | +2% | +1% |
| find_incomplete_by_key | ADMIN | 0.122M | 0.171M | 0.157M | -8% | +29% |
| delete_by_key_status | RET | 0.112M | 0.097M | 0.113M | +16% | +1% |
| find_completed_before | RET | 1024.725M | 252.790C | 241.306C | -5% | **-76%** ? |
| ids_completed_before | RET | 315.732M | 249.427M | 238.608M | -4% | **-24%** |
| find_failed_before | RET | 688.242M | 2.942C | 3.197C | +9% | **-100%** ? |
| ids_failed_before | RET | 266.940M | 0.615C | 0.674C | +10% | **-100%** ? |
| delete_by_status_failed | RET | 14101.348C† | 15097.085C† | 13184.864C† | -13% | -6% |
| delete_by_ids | RET | 4.650C | 4.820C | 4.890C | +1% | +5% |

## Write cost (S2, committed single-row writes; PostgreSQL `writecost`)

Files: `results/<variant>/postgres/S2-R2/writecost.csv` (main, v1, v1b); repeat (fresh prepare) `results/{v1,v1b}/postgres/S2-wc2-R2/writecost.csv`, shown as `(2)`. Deltas are against v1b (pass 1). Index sizes: `index_bytes_*` lines of the same files (MiB). 5 000 writes per phase; `indexes after` = after 15 000 writes.

| metric | main | v1 | v1(2) | v1b | v1b(2) |
|---|---:|---:|---:|---:|---:|
| insert 1 thread p50 ms | 1.92 (-6.9%) | 2.02 (-2.1%) | 1.91 (-7.2%) | 2.06 | 2.04 (-0.7%) |
| insert 1 thread WAL B/write | 6577 (-2.3%) | 6642 (-1.3%) | 6722 (-0.1%) | 6731 | 6696 (-0.5%) |
| insert 4 threads p50 ms | 1.86 (-0.2%) | 2.01 (+8.1%) | 1.84 (-0.9%) | 1.86 | 1.85 (-0.6%) |
| insert 4 threads rec/s | 1801 (-0.7%) | 1679 (-7.4%) | 1817 (+0.2%) | 1813 | 1771 (-2.3%) |
| insert 4 threads WAL B/write | 6549 (-4.4%) | 6831 (-0.2%) | 6846 (-0.0%) | 6848 | 6862 (+0.2%) |
| upd_complete p50 ms | 1.93 (-9.8%) | 2.16 (+1.0%) | 2.04 (-4.7%) | 2.14 | 2.13 (-0.3%) |
| upd_complete rec/s | 507 (+8.3%) | 450 (-3.9%) | 490 (+4.8%) | 468 | 457 (-2.3%) |
| upd_complete WAL B/write | 7590 (+0.5%) | 7520 (-0.4%) | 7515 (-0.5%) | 7552 | 7507 (-0.6%) |
| indexes incl. PK (count) | 6 | 6 | 6 | 6 | 6 |
| indexes total before, MB | 294.7 (+6.6%) | 276.5 (-0.0%) | 276.5 (-0.0%) | 276.5 | 276.5 (+0.0%) |
| indexes total after 15k writes, MB | 298.2 (+6.0%) | 281.2 (-0.0%) | 281.2 (-0.1%) | 281.4 | 281.5 (+0.0%) |

Noise from the repeat: WAL per write moves by 0.0-1.2 % between pass 1 and pass 2 (v1: insert 1 thread 6642 -> 6722, v1b: 6731 -> 6696), latency p50 by 0.3-8.7 % (v1 insert 4 threads 2.01 -> 1.84). Reading: v1 vs v1b write cost is the same. Against main both write 1.3-2.3 % (1 thread) and 4.4-4.6 % (4 threads) more WAL per insert; upd_complete WAL equal (-0.5 %); main's single-thread latency is 7-10 % lower than pass 1 of v1/v1b but v1(2) (1.91 ms) is within that range, so no latency difference is established. Index count is 6 (PK included) in all three; v1/v1b hold 18 MiB less than main (`index_bytes_before.*`: main 73.8 + 64.2 MiB record-key indexes, v1/v1b 117.8 MiB single record-key index; queue index 1.9 MiB).

Per-index sizes before writes (MiB, v1b): PK 73.3, record-key `(record_key, completed_at, created_at, id)` 117.8, `idx_status_retry` 38.3, `idx_partition_status_retry` 38.4, `idx_status` 6.7, queue partial 1.9 (`results/v1b/postgres/S2-R2/writecost.csv`).

## S9 churn (one run per variant, 500 000 inserts, no manual VACUUM)

`results/{v1,v1b}/postgres/S9-R2/churn.txt`: v1 423 s, v1b 407 s (-4 %, single run, no noise estimate); table size after churn 1 735 MB in both; indexes total after 603 MB (v1) vs 543 MB (v1b); the queue index grows from 1.9 MiB to 94 MB (v1) / 78 MB (v1b) without vacuum; dead tuples 133 098 / 134 305. Autovacuum ran 3 times in both. No difference in table growth. The 60 MB total index difference is spread over every index (queue 78 vs 94 MB, record-key 216 vs 247 MB, PK 119 vs 126 MB, `idx_status_retry` 52 vs 55 MB), including indexes that are identical in both variants, so it is most likely run-to-run bloat variation, not a v1b effect; single run.

## Plans (excerpts; full plans in `results/<variant>/postgres/<label>-R2/plans/`)

`keys_strict`, S7 (FAILED-heavy; 390 000 FAILED): v1 `Index Only Scan using idx_outbox_record_created_id_incomplete` under a Nested Loop Anti Join, 2 288 buffers, 12.3 ms (`results/v1/postgres/S7-R2/plans/keys_strict.txt`); v1b `Index Only Scan using idx_outbox_record_status_created_id_incomplete`, 5 buffers, 0.188 ms (`results/v1b/postgres/S7-R2/plans/keys_strict.txt`). The status-leading key lets the planner start at `status = 'NEW'` instead of walking FAILED entries.

`keys_strict`, S8-S4: v1b same shape, `Index Only Scan` on the queue index + record-key index anti join, 0.129 ms (`results/v1b/postgres/S8-S4-R2/plans/keys_strict.txt`).

`keys_nonstrict`, S8-S2: v1 Nested Loop Anti Join over the ordered `created_id_incomplete` scan (273 buffers); v1b Hash Right Anti Join with a top-N sort (56 buffers, 0.50 ms in the plan) (`results/{v1,v1b}/postgres/S8-S2-R2/plans/keys_nonstrict.txt`). The plan is captured with the first parameter set only.

`pages` S8-S4: main `Incremental Sort` over `idx_outbox_record_record_key_created`; v1 `unnest` + per-key lateral index scan; v1b a single `Index Scan using idx_outbox_record_record_key_completed_created_id` without Sort (0.075 ms) (`results/{main,v1,v1b}/postgres/S8-S4-R2/plans/pages.txt`). `pages` S5 v1b: Sort + bitmap scans of the record-key index and `idx_outbox_record_status` (`results/v1b/postgres/S5-R2/plans/pages.txt`).

## Plan-mode caveat

`plancache.csv` over all round 2 result directories (all variants, including the forced-mode runs) has 954 query-label cells with 4 custom + 16 generic timed reps (`M`), 410 all generic (`G`) and 1 605 all custom (`C`). Cells where main, v1, v1b differ in mode are marked `?`; they are not evidence about the variant. Examples: S8-S4 `count_failed` (G 21.8 ms vs C 0.25 ms on identical SQL), S4/S5 `count_new` (main `M`, v1/v1b `C`). The dedicated analysis is `experiments/R2-plan-modes.md` (forced custom/generic runs). The keys_strict/keys_nonstrict cells compared above are `C` in every variant and label except main S8-S4 and S4 keys_nonstrict (`M`).

## Portability notes

The status-leading partial index is PostgreSQL syntax (`WHERE completed_at IS NULL`, `INCLUDE`). SQL Server has an equivalent filtered index with `INCLUDE`; MySQL, MariaDB, H2 have no partial indexes (round 1 E02a, non-partial, gives the same read result at 143 MB); Oracle needs a function-based/`status`-leading composite index. Not measured here. `pages` `IN (...)` is portable SQL (no `unnest`/`LATERAL`), which is a portability advantage of v1b over v1.

## Follow-ups

- Repeat S8-S2 `keys_nonstrict` (v1b 1.445 ms vs main 0.991) to see whether +46 % is real; plan shows Hash Right Anti Join + sort instead of the ordered scan.
- Capture plans during S10 for `pages` (0.7 ms vs 14 ms) with the `IN` form; compare against `unnest`/`LATERAL` in the same run.
- S5/S6/S7 with partial ownership (`--owned mod4`) are missing; round 3 spec makes partial ownership primary.
- Drop-index ablation on v1b: `experiments/E05-drop-indexes.md`; partial record-key index and minimal set: `experiments/E04-partial-record-key.md`.
