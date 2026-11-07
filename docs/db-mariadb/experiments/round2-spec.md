# Round 2 spec — the 4th index and UUIDv7 (screen, 2026-11-06)

Orchestrator spec. Stage **screen** (one pass). Brief `briefs/06-experiment.md`. Inputs: `03-main-vs-branch.md`,
`experiments/r1a-index-sets.md`, `experiments/r1b-forms.md`. Noise thresholds and host-load rule: `STATE.md`.

## Question

Which 4th index brings `count_partitions` at partial ownership back to about main's level at the lowest write cost? Does UUIDv7 id
generation change that answer?

Reference values:
- **Front-runner without a 4th index:** `count_partitions` S16-S4 106 ms, S8-S4 114 ms, S8-S5 25 ms.
- **main:** 7.5 ms, 61 ms, 8.7 ms on the same labels.
- **`(status, partition_no)` in round 1a:** about 7 ms on S16-S4, but S4-wc insert redo 2.3 -> 5.7 KB and S2-wc completion/delete redo
  2-4x.

Hypothesis: with random UUIDv4 ids the effective key `(status, partition_no, id)` puts inserts and completions at random positions
inside each `(status, partition)` group. `created_at` after `partition_no`, or time-ordered UUIDv7 ids, turn them into per-partition
appends.

**User decision (2026-11-06): a 4th index is kept.** P2 reads matter, and the current slow `count_partitions` is not accepted. The
round picks the cheapest 4th index. The runs without a 4th index are the reference that prices it.

## Fixed in every run: base (a)

- Indexes (`experiments/r1/r1-m1d/ddl.sql`):
  - i1 `idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`;
  - i2 `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)`;
  - i3 `idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no)`.
- Queries: the branch SQL (`--catalog-variant mdb-opt`) plus the overlays in `experiments/r2/forms-a/queries/`. These are copies of
  `Q-merged` forms, renamed to the query id:
  - `keys_strict` = `ks-05-head`;
  - `keys_nonstrict` = `kn-05-head`;
  - `oldest_created` = `oc-03-order-id`;
  - `oldest_due_nonstrict` = `odn-01-order`.

## Runs (part A)

| Run | 4th index | Ids |
|---|---|---|
| `r2-base-v4` | none (reference) | UUIDv4 (default) |
| `r2-sp-v4` | `idx_outbox_record_status_partition (status, partition_no)` | UUIDv4 |
| `r2-spc-v4` | `idx_outbox_record_status_partition_created (status, partition_no, created_at)` | UUIDv4 |
| `r2-base-v7` | none (reference) | UUIDv7 (`HARNESS_ID_VERSION=7`) |
| `r2-sp-v7` | `(status, partition_no)` | UUIDv7 |
| `r2-spc-v7` | `(status, partition_no, created_at)` | UUIDv7 |

DDL files: `experiments/r2/<base|sp|spc>/ddl.sql`, i.e. base (a) plus the 4th index. Check that each applies on 12.3.3.

**UUIDv7:**
- `HARNESS_ID_VERSION=7` must be set for `gen` **and** for every `bench`, `writecost` and `concurrency` step of the v7 runs. Inserts
  generate ids in `Params.kt`.
- The harness keeps one snapshot per lane, and `gen` replaces it. Run all v4 blocks first, then all v7 blocks, regenerating per scenario.
- Before each timed step, check that the snapshot's id version matches the run. The snapshot marker records `ids=uuidv7`; if `prepare`
  does not check it, check it in the driver. A wrong-version cell is invalid.
- Record the id version in every result tree, e.g. in `env.txt` or a `ids.txt` next to it.

## Measurements

1. **Write cost, the main criterion:** `S2-wc` and `S4-wc` per run.
   - Report redo bytes per write, pages written per write, latency p50/p95 per phase, 4-thread insert throughput.
   - Report every index's size before and after the writes: page-split growth of the 4th index and of the primary key.
2. **Reads:** labels `S8-S4`, `S16-S4`, `S8-S5`, `S4`, `S5`, groups `HOT,WRITE,MON`.
   - Per-query limit 10 s on S4, S5, S8-S4, S16-S4; elsewhere max(2 x reference median, 60 s).
   - `pages` on S4 with `--pages-keys-file experiments/r1/s4-pages-keys.txt`.
   - Per scenario block and id version: the reference (`r2-base-*`) first and again last (drift check).
3. **Concurrency:** `S10-S2-p1-unthr` per run (solo cell).
4. **Plans:** for `r2-sp-*` and `r2-spc-*` on S5 data, `ANALYZE FORMAT=JSON` of `count_partitions`, `count_statuses`, `keys_strict`,
   `keys_nonstrict`, `oldest_created`, `oldest_due_strict` and `oldest_due_nonstrict` with 16, 199, 200 and 256 owned partitions.
   - Use the driver's `SQL_SELECT_LIMIT`.
   - Report the chosen index and `rows` vs `r_rows`.
   - Purpose: show that the 4th index never wins a plan with a false estimate (the round-1a R1 failure).
5. **Equivalence:** `forms-a` against `mdb-opt` on S1 and S5, with v4 and with v7 data.

## Part B — `kn-05-head` with non-due heads (risk check, user request)

`kn-05-head` finds a key's first NEW, due, unfinished record via i2 `(record_key, completed_at, created_at, id)`. `status` and
`next_retry_at` are not in i2, so each skipped record costs a primary-key lookup. A key whose oldest unfinished records are all scheduled
in the future makes the subquery read far, once per candidate. The branch `NOT EXISTS` form (`kn-00-base`) has a similar probe. No
scenario contains this case yet.

Derived data `S5-ndK`:
- Start from the S5 snapshot, v4, on the base (a) DDL.
- After `prepare`, for every hot key set `next_retry_at = T0 + 1 h` on its K oldest NEW records (K = 10, 100, 1 000, of 2 000 per key).
  T0 is the harness reference time used for `:now`.
- Run `ANALYZE TABLE`, then bench **without** `--prepare`. Re-prepare for each K, or revert the update.
- Time `keys_nonstrict` as `kn-05-head` and as `kn-00-base` (branch SQL), at 8 and 256 owned partitions (`--owned` as for S8-S5 and S5).
- `keys_strict` is not part of B (user, 2026-11-06). In strict mode a non-due head blocks the rest of its key, so only the head can be in
  the future, and S5 already covers that (20 keys with a future NEW head). In non-strict mode the scheduler continues past failed records
  (`continueOnFailure()` in `OutboxProcessingScheduler`), so K > 1 future-scheduled records at the head of a key are realistic.
- Report medians, rows examined (handlers) and plans.
- Verdict: does `kn-05-head` stay at or below `kn-00-base` at every K? If not, from which K does it lose?

## Part C — equivalence of the second-variant head forms (no timings)

`Q-merged/keys_strict/ks-11-head-no-outer-completed.sql` and `Q-merged/keys_nonstrict/kn-14-head-no-outer-completed.sql` are `ks-05` /
`kn-05` without the outer `completed_at IS NULL`. They form pack `forms-e5h`, together with `oc-08-no-completed-order`,
`odn-06-no-completed-order` and `ods-09-no-outer-completed`. Run equivalence for `forms-e5h` on DDL `experiments/r1b/b-e5l/ddl.sql`
against `mdb-opt` on S1, S5 and S7 (v4). These forms are confirm candidates next to (a). Equivalence may run in lane 2 while lane 1
is not timing.

## Decision rule

Among `sp` and `spc`, choose per id version the 4th index with the lowest write cost. To qualify, it must:
- bring `count_partitions` on S16-S4 and S8-S5 to within about 2x of main;
- not worsen P1 reads, P2 reads or S10 beyond the noise threshold against the reference of the same id version;
- show no false-estimate plan in measurement 4.

Report the write-cost price of the winner against the reference: redo delta per phase, latency delta, index bytes. Report whether v7
changes the ranking. If neither index qualifies, say why and which cells fail.

## Report

`experiments/r2-fourth-index.md`, in brief 06 format:
- v4 and v7 sections, each with write cost first, then partial ownership reads, then full ownership reads, S10 and plans;
- part B, then part C;
- a ranking;
- deferred-test candidates;
- a proposed confirm list. After round 2, the confirm stage runs:
  - candidate 1: (a) + winner;
  - candidate 2: `b-e5l` DDL + `forms-e5h` + winner;
  - in the winning id version.
