# Phase 5 — round 5 spec (PostgreSQL): user ideas

Requested by the user on 2026-11-03. Runs after the round 4 screen, before any round 4 confirm, because idea 3 can change the reference. Mechanics and protocol as round 3 stage 3 (`harness/bin/round3-validate-pg.sh`): DDL and query overrides only, two passes in reversed order, container restart between passes, warmup 5, reps 20, `--prepare`, S10 with 4 pollers, 60 s, `--capture-plans`. Suffix `-R5`.

All variants are in `experiments/R5-<variant>/{ddl.sql,queries/}`, catalog `v1`, worktree `outbox-wt-v1`. Base is `UABC-L`.

| Variant | Change vs `UABC-L` | Idea |
|---|---|---|
| `UABC-L` | none | reference; 1b, 2b, 3a |
| `P-IN` | `pages` = `IN` + `ORDER BY record_key, created_at, id` | 1a |
| `P-UNO` | `pages` = 1b + top-level `ORDER BY o.record_key, o.created_at, o.id` | orchestrator addition: order guaranteed by SQL; not requested by the user |
| `OD-OB` | `oldest_due_nonstrict` = `ORDER BY next_retry_at LIMIT :limit` | 2a |
| `UABCD-L` | + `(status, partition_no)` | 3b |
| `D3` | + `(status, partition_no) WHERE completed_at IS NULL` | round 4 survivor (`R4-screen.md`); compared with 3a and 3b |
| `E40` | UUIDv7 ids, own snapshot per scenario (`HARNESS_ID_VERSION=7`) | round 4 survivor; adds the read matrix |

## Runs per pass

- `UABC-L`, `UABCD-L`, `D3`, `E40`: full round 3 validate matrix. S8-S2, S2, S2-wc, S10 on S2 (2 runs), S9; S8-S4, S4 (all groups, so `count_statuses_all`), S4-wc, S10 on S4 (2 runs); S8-S5, S5; S8-S6; S8-S7.
- `P-IN`, `P-UNO`: `--only pages,keys_strict` on S8-S2, S8-S4, S8-S5, S8-S6, S8-S7, S5; S10 on S2 and S4 (2 runs each). Plan check: `pages` plans must use neither queue index; count slow plans.
- `OD-OB`: `--only oldest_due_nonstrict,keys_strict` on S8-S2, S8-S4, S8-S5, S8-S6, S8-S7, S4, S5. Also `plancache.csv` modes: E1M's gap was mostly planning time.
- Equivalence vs v1 on S2, S5, S7 for all four changed variants. For `OD-OB`, an empty result is zero rows where `MIN` gives one NULL row: check how the repository maps both before calling it equivalent.

## Reports

- `experiments/R5-pages-form.md`: 1a vs 1b vs `P-UNO`. Earlier evidence to compare: `R3X-screen-extension.md` X0 (the same `IN` form on S2, slow passes 2 of 4), `E1P-pages-default-pg.md`.
- `experiments/R5-oldest-due.md`: 2a vs 2b. Earlier evidence: E1M (MIN faster on the v1 indexes).
- `experiments/R5-uuidv7.md`: E40 vs `UABC-L`, reads and WAL. Explain the WAL drop and the larger primary key if the data allow.
- `experiments/R5-d2-literal.md`: 3a vs 3b vs `D3` in the table layout of `U-index-set.md`, including which index `count_partitions` and `count_statuses_all` use, and whether `pages` goes to `BitmapAnd`.
