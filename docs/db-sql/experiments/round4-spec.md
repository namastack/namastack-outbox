# Phase 5 round 4 spec (PostgreSQL)

Reference: `UABC-L` (`U-index-set.md`), also the base of all variants, override dirs `experiments/R4-<variant>/`, suffix `-R4`. Staged: screen, then confirm and validate only for what survives. Isolation and noise rules as in `STATE.md`. Partial ownership first.

## Targets

1. **S8-S5 `keys_strict` ~165 ms**, largest P1 cell left. S5 at full ownership: 1.2 ms. When owned partitions hold fewer keys than `:limit`, the scan cannot stop early: 18 000 candidates probed, 166k rows filtered by partition, 115k buffers.
2. **P2 cost of the literal form** without the `pages` `BitmapAnd` of D2.
3. **Write path**: WAL, bloat, throughput on a large queue.

## Variants

| Variant | Change vs UABC-L | Question |
|---|---|---|
| `UABC-L` | – | reference |
| `E08a` | E08 per-partition `LATERAL` on `keys_strict`, `keys_nonstrict` (text in `ideas.md` E08), A replaced by `(partition_no, status, created_at, id) INCLUDE (next_retry_at, record_key) WHERE completed_at IS NULL` | early stop per partition; does `LATERAL` rescue the `partition_no`-leading A? `oldest_created` may need A's old form: report |
| `E08A` | E08 SQL, A unchanged | does `LATERAL` alone help? |
| `K3` | `keys_strict`: candidate subquery capped (`ORDER BY created_at, id LIMIT 1000`) before the anti-join | bounds the scan. **Not equivalent by design**: can return fewer than `:limit` keys although more exist. The globally oldest head is always among candidates, so order holds and no key starves. Report, do not recommend without the user |
| `D3` | + `(status, partition_no) WHERE completed_at IS NULL` | `count_partitions` near D2 without COMPLETED rows? Does `BitmapAnd` return on `pages`? |
| `E07` | per-table autovacuum settings (`ideas.md` E07) | bloat, heap fetches, S10 |
| `E07b` | E07 + `fillfactor = 85` | more HOT updates, lower WAL? Heap +18 % |
| `E40` | UUIDv7 `id` (`ideas.md` E40) if the harness can generate it cheaply; otherwise report the work needed and skip | insert WAL, PK size |

Dropped candidate: statement cache of `IN` lists (`pages` is `unnest`; `count_partitions` and `keys_*` remain `IN`, measured fine).

## Screen

`--groups HOT,MON`, warmup 5, reps 20, `--prepare`, rotated order: S8-S2, S8-S4, S8-S5, S8-S6, S8-S7, S16-S4. Write cost on S2 and S4 for every DDL change, twice for E07, E07b, E40 and the reference (WAL outliers occur). S9 and S10 on S4 (4 pollers, 2 runs) for E07, E07b, E40, reference. Equivalence vs v1 on S2, S5, S7 for all with changed SQL (K3: report differences, expected).

Stop rule: `R3`-style; a variant survives only if a P1 cell or S10 gains above noise without a P1 loss.
