> **Superseded by round 5:** the recommended set is now `UABC-L` + `(status, partition_no) WHERE completed_at IS NULL` (`D3`), see `R5-d2-literal.md`.

# Round 3 verdict: P1/P2 index set (PostgreSQL)

Stage 3 validate: `harness/bin/round3-validate-pg.sh`, 4 h 24 min, 0 failures, 0 `PLAN CHECK BAD`, 0 slow `pages` plans in 32 S10 runs. Two passes, order reversed, container restarted. Results `results/<variant>/postgres/<label>-v<1|2>-R3V/`. Cells: pass 1 / pass 2, median ms. Reference `v1c` = v1b DDL with `unnest` `pages`. Screen: `R3-screen.md`, `R3X-screen-extension.md`. Confirm: `R3C-confirm.md`. Equivalence vs v1 passed on S2, S5, S7 in confirm.

## Recommendation: `UABC-L`

```sql
CREATE INDEX idx_outbox_record_status_created_id_incomplete ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL;
CREATE INDEX idx_outbox_record_key_created_id_incomplete    ON outbox_record (record_key, created_at, id) WHERE completed_at IS NULL;
CREATE INDEX idx_outbox_record_status_retry_incomplete      ON outbox_record (status, next_retry_at) INCLUDE (partition_no) WHERE completed_at IS NULL;
CREATE STATISTICS outbox_record_status_completed_stx (mcv) ON status, completed_at FROM outbox_record;
```

SQL: `pages` = `unnest` + `LATERAL`, inner `ORDER BY r.created_at, r.id`. `oldest_due_strict`, `oldest_due_nonstrict`, `count_statuses` (NEW/FAILED), `count_partitions` get `AND completed_at IS NULL`.
PostgreSQL only so far; other dialects are phase 6.

## v1c vs D2 vs recommendation

| | v1c | UABCD2 | UABC-L |
|---|---|---|---|
| **S10 on S4, mean proc/s (range, 4 runs)** | 973 (921–1007) | 1149 (1070–1247) | 1113 (1029–1287) |
| S10 on S2, mean proc/s | 1203 (1158–1236) | 1155 (1131–1194) | 1331 (1212–1592) |
| S4 index MB | 702 | 465 | 451 |
| S4 WAL insert | 7453 / 7410 | 7299 / 7277 | 6892 / 6869 |
| S4 WAL `upd_complete` | 16008 / 17723 | 9434 / 9422 | 8990 / 8987 |
| S9 index MB after churn | 536 / 571 | 316 / 358 | 343 / 324 |
| **S8-S5 `pages`** | 116 / 119 | 132 / 130 | **60 / 61** |
| S8-S5 `keys_strict` | 178 / 179 | 163 / 164 | 164 / 165 |
| S8-S4 `keys_strict` | 1.07 / 1.01 | 0.93 / 0.81 | 0.85 / 0.92 |
| S8-S4 `count_statuses` | 38 / 38 | 39 / 39 | 51 / 51 |
| S8-S4 `count_partitions` | 16 / 16 | 10 / 10 | 48 / 49 |
| S8-S5 `count_partitions` | 2.7 / 2.8 | 1.9 / 1.8 | 16 / 16 |
| S4 `count_statuses_all` | 71 / 71 | 73 / 73 | 304 / 306 |

Result:
- **Final win vs v1c, partial ownership:** S10 on a large queue +14 %, `upd_complete` WAL −47 %, insert WAL −7 %, index size −36 %, S8-S5 `pages` −48 %.
- **Cost vs v1c, P2 only:** `count_partitions` S8-S4 +32 ms, S8-S5 +13 ms; `count_statuses` S8-S4 +13 ms.
- **No difference:** `keys_*`, `pages` outside S5, write latency cells, `oldest_*`, S10 on S2.

## D2 or literal

Recommended: literal, by the P1-first rule. D2 makes `pages` twice as slow on S5/S8-S5 (`BitmapAnd`, `plans/pages.txt`, 2 of 2 passes) and costs 5–6 % WAL. Throughput on S4 shows no difference between them.
What the literal form gives up:
- `count_partitions` 48 vs 10 ms on 1M NEW, scraped per metrics interval rather than per poll.
- Forced generic plans: `count_partitions` 317 ms on S8-S4 (`R3C-confirm.md`). Default mode stayed custom.
- `count_statuses_all` (COMPLETED configured) has no index: parallel seq scan. 304 ms with the heap in OS cache, **1.8–2.0 s** in confirm with the same plan and buffers (shared `read=210k` both times; cache as cause is inferred). This is the strongest argument for D2, and the deciding question for anyone including COMPLETED in `record-statuses`.

## Index by index

| Index | Used by | Keep? | Evidence |
|---|---|---|---|
| A (with INCLUDE) | `keys_*`, `oldest_created` | keep | A3 costs S8-S5 `keys_strict` 253 vs 165 ms (23 601 heap reads), saves 63 MB on S4 |
| B1 partial key | `pages`, strict anti-join probes | keep | add-one in round 2 |
| C (INCLUDE `partition_no`) | `oldest_due_*`, literal `count_partitions` | keep | without C `oldest_due_strict` 198 ms; C3 gives `count_partitions` 119 ms without D2 |
| Statistics | `keys_nonstrict` on S2 | keep | without: S2 1.68 / 1.75 vs 1.07 / 1.08, plan differs, 3 of 3 S2 runs including confirm (one variant with statistics also showed 1.74 once, UABC-L-UNNS0 in confirm); S8-S2 no difference; no write cost. Corrects the "drop candidate" of `R3C-confirm.md` |
| D1, D2, PD | counts | out | see above |
| v1b `idx_status`, `idx_status_retry`, `idx_partition_status_retry`, full key index | | out | replaced; 702 → 451 MB |
| B2, C2, A2, A3, C3, PA*, PC*, PL-*, PAC | | out | `R3-screen.md`, `R3X-screen-extension.md`, `R3C-confirm.md` |

## Corrections and closed items

- `upd_retry` and `delete_by_id` "regression on S4" in confirm did **not** reproduce: S8-S4 `upd_retry` v1c 0.40 / 0.33, UABC-L 0.30 / 0.32; `delete_by_id` 0.14 / 0.16, 0.14 / 0.14. Single-run noise.
- `count_statuses_all` 1929 ms in confirm: same plan as 305 ms here; see above.
- Noise example: UABC-L-NS S4 `upd_complete` WAL 10696 / 8985, v1c S2 6170 / 7509. Outliers occur, so WAL needs 2+ runs.

## Limits

No cold-cache runs. S10 uses 4 pollers on one host. Single S10 on S2 runs of UABC-L (1592) and UABC-L-NS (1495) jump without explanation (keys p50 halves), which inflates their means; S2 differences are not meaningful.
