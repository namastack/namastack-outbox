# Round 3, stage 0: `pages` form in the processing loop

S10 loop, pollers 4, rate 0, 60 s, DDL v1b, 4 passes per combination, rotated order. `def` = default, `np` = `max_parallel_workers_per_gather = 0`.
Results: `results/<form>/postgres/S10-<form>-<mode>-p<n>-R3/concurrency-p4-r0.txt`, plans in `concurrency-p4-r0.plans.txt`.

| Form | Mode | `pages` p50 per pass (ms) | Fast passes | Throughput per pass (rec/s) |
|---|---|---|---|---|
| `PF-UN` (`unnest` + `LATERAL`) | def | 1.30, 1.14, 1.31, 1.33 | 4 of 4 | 1109, 1310, 1193, 1124 |
| `PF-UN` | np | 0.58, 1.32, 1.34, 1.27 | 4 of 4 | 1426, 1166, 1186, 1193 |
| `PF-IN` (`IN` + `ORDER BY`) | def | 5.13, 16.49, 1.12, 8.83 | 1 of 4 | 1143, 865, 1153, 1104 |
| `PF-IN` | np | 16.41, 12.02, 7.38, 15.53 | 0 of 4 | 908, 1068, 1170, 901 |
| `PF-NS` (`IN`, no `ORDER BY`, client sort) | def | 15.09, 16.55, 13.08, 8.90 | 0 of 4 | 937, 888, 1084, 1069 |
| `PF-NS` | np | 16.85, 0.53, 16.84, 1.16 | 2 of 4 | 864, 1430, 900, 1141 |
| `PF-ANY` (`= ANY(ARRAY)`, no `ORDER BY`, client sort) | def | 2.13, 14.57, 1.11, 1.13 | 3 of 4 | 1370, 894, 1133, 1189 |
| `PF-ANY` | np | 14.99, 16.09, 15.96, 1.17 | 1 of 4 | 940, 928, 917, 1158 |

## Findings

- **The parallel-plan hypothesis is refuted.** Slow passes occur with parallelism off (`PF-IN` np: 4 of 4 slow).
- **Cause: index choice.** Slow pass (`PF-IN/.../S10-PF-IN-np-p1-R3`): custom plans (`custom_plans=1373 generic_plans=0`), `Index Scan using idx_outbox_record_status_created_id_incomplete`, `Index Cond: status = 'NEW'`, `Filter: record_key = ANY(...)`, estimated cost 35, rows 1. The whole queue is walked and filtered, 25–30 ms at the end of the run. Fast pass (`PF-IN/.../S10-PF-IN-def-p3-R3`): generic plans (`custom_plans=5 generic_plans=1742`), index scan on the record-key index, execution 0.3–0.6 ms.
- A pass settles on one of the two after the first 5 custom plans, which explains the per-pass flip. `PF-ANY` has the same flip, so the single-statement array form does not help.
- `PF-UN` forces one probe per key, so it cannot pick the queue index: 8 of 8 fast.
- **Removing `ORDER BY` (user idea 1) does not fix it:** `PF-NS` is slow in 6 of 8 passes.
- Isolated bench cells do not show it: 20 reps on a freshly analyzed table.

## Not established

- Whether the low estimate comes from stale statistics during the 60 s run (the NEW set grows quickly at rate 0). Not measured; autoanalyze in a longer run could change how often the bad plan appears, but the loop can run into it, so the risk is real.
- The cost of a wrong choice grows with the NEW backlog.

## Verdict

**Keep `unnest` + `LATERAL` on PostgreSQL.** The E1P verdict (override not needed) is withdrawn. Pages cells of `IN` form variants in the isolated screen say nothing about this risk. Other dialects must be checked for the same index choice in phase 6, as they use `IN`.
