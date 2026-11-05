# Plan: `pages` follow-up, then the remaining databases

Agreed with the user on 2026-11-05. **Not started; waiting for the user's go.** Serial, one benchmark at a time. Comparison stays `cmp-main` (`f4446c8`) vs `cmp-jdbc-opt` (`01165d15`); the branch is not changed. Timings for the HOT, WRITE and MON groups only, no retention or admin timings. JPA is not measured.

## Before starting

1. Add the per-query limit to the harness (rule below) and check on S1 that it works, H2 included.
2. Make the skip logic of `harness/bin/cmp-dialects.sh` accept a finished step that has a stopped query.
3. Move `harness/build/h2/bench.mv.db` (16 GB) to `/run/media/zamo/T7/outbox-bench/` (user decision). Free disk is 7.9 GB, about 24 GB after the move. `gen` replaces this file, so it is only a backup and is not needed to resume.

## Per-query limit

Applies to each execution, on H2 and the other databases.

| Run | Limit |
|---|---|
| `cmp-main` | 15 min |
| `cmp-jdbc-opt` | 2 × `cmp-main` median for the same query and label, minimum 60 s |
| `cmp-jdbc-opt`, `cmp-main` stopped or has no timing for the query | 15 min |
| Equivalence | 15 min |

- A stopped query is recorded as "over `<limit>`", without a timing, and the remaining queries still run.
- `cmp-main` runs first on each label, so its medians exist when the branch starts. Existing `cmp-main` results (for example H2 `S4`) are reused.
- The 6 h step limit stays as a backstop.

## Step 1: PostgreSQL, `pages` forms

Driver `harness/bin/pkk2-pg.sh`, about 2.5–3 h. Two passes, reversed order, `--only pages,keys_strict`.

| Variant | `pages` form |
|---|---|
| `cmp-jdbc-opt` | branch, unchanged (reference) |
| `P-KK` | top-level `ORDER BY k.k, o.created_at, o.id`, inner `ORDER BY` kept |
| `P-KK2` | top-level `ORDER BY`, no inner `ORDER BY`, `OFFSET 0` |
| `P-KK3` | top-level `ORDER BY`, no inner `ORDER BY`, no `OFFSET 0` |

- Rerunning `P-KK` on S8-S5 (18 000 rows) checks whether its +20 % was real. It is called real but small if `P-KK` is slower than the reference in both new passes; +20 % stays below the 30 % noise threshold.
- `P-KK2` or `P-KK3` is acceptable if it has no `pages` regression above the threshold on S8-S5 and S5, S10 throughput within range, no slow `pages` plans, no queue index or `BitmapAnd` in S10, and equivalence passing. If both qualify, prefer `P-KK3` because it is simpler.
- Result: report in `experiments/`. Adoption stays the user's decision.

## Step 2: H2

Run in this order, resuming from the existing results (S1, S2, S8-S2, S2 write cost, `cmp-main` S4):

1. Branch S4. The stuck query should now stop after about 60 s.
2. S8-S4, `cmp-main` then branch.
3. S5 with equivalence, then S7.

**Disk:** S4 steps grew the H2 file by up to 12 GB, and it does not shrink. After `gen`, copy the clean file to the T7 disk, and restore it to `harness/build/h2/` between steps, so every step starts from the same snapshot. H2 itself always runs on the internal disk: T7 is a USB disk, and timings from it would not be comparable with the existing H2 results. Remove the copies from T7 when H2 is finished.

## Step 3: Other databases

MariaDB, MySQL, SQL Server, Oracle, in that order, resumable with `bin/cmp-dialects.sh`. S1 is already done. Cells: S2, S8-S2, S2 write cost, S4, S8-S4, S5, equivalence S5, S7. Same per-query limit, including the 60 s minimum, unless H2 shows it stops healthy queries.

- **Disk:** about 24 GB is free once the H2 file is gone, minus each database's image, and MySQL S2 alone used about 14 GB earlier. The driver stops safely below 5 GB and can be resumed. Check free space when each database starts, and look at `keys_nonstrict` when each reaches S4.
- If a database fails, carry on with the next one and report it. The branch's SQL and DDL are not fixed along the way.
- Result: main vs branch per database in `06-cross-dialect.md`, `STATE.md` updated.

## Open for the user

- Adopt a new `pages` form on the branch, after step 1.
