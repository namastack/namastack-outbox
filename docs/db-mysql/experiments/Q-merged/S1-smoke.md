# S1 smoke of the merged query forms

Date 2026-11-06, lane 1, scenario S1, DDL and catalog of `my-opt` (worktree `/home/zamo/git/outbox-wt-my-opt`), `--groups HOT,WRITE,MON`, `HARNESS_SAMPLE_ORDERED=1` (default).
Per form: `bin/form-overlay.sh <QM file> <dir>`, then `bin/bench equiv --a my-opt --b <QM name> --catalog-variant-b my-opt --queries-b <dir> --only <id>` (the form versus the `my-opt` catalog query of the same id)
and `bin/bench bench --variant <QM name> --catalog-variant my-opt --queries <dir> --only <id> --label S1 --prepare` (timing + plan capture). The `-0` reference files were not run.
Results: `results/smoke/equiv/mysql/S1/my-opt-vs-<QM name>.md`, `results/smoke/<QM name>/mysql/S1/{timings.csv,plans/}`. The timings are single smoke runs on a 10 000-row table, not measurements.
A single all-NULL row equals an empty result in the generic comparison (`equiv` normalizes it); it was not needed by any form on S1.

**Totals: 66 forms, 66 PASS, 0 WARN, 0 FAIL, 0 ERROR.** Every form parsed, returned the same result as the reference, and produced timings and plan files (`plans/<id>.txt|json|trace.json|summary.txt`) with the tokens expanded.

| form | equiv | detail | bench median ms | p95 ms | plan files |
|---|---|---|---|---|---|
| QM-count_partitions-1 | PASS | 1 row(s) = [180] | 3.859 | 4.437 | 4 |
| QM-count_partitions-2 | PASS | 1 row(s) = [180] | 1.952 | 2.650 | 4 |
| QM-count_partitions-3 | PASS | 1 row(s) = [180] | 1.940 | 2.517 | 4 |
| QM-count_partitions-4 | PASS | 1 row(s) = [180] | 3.977 | 4.486 | 4 |
| QM-count_partitions-5 | PASS | 1 row(s) = [180] | 2.709 | 3.238 | 4 |
| QM-count_partitions-6 | PASS | 1 row(s) = [180] | 3.128 | 3.631 | 4 |
| QM-count_partitions-7 | PASS | 1 row(s) = [180] | 2.565 | 3.322 | 4 |
| QM-count_partitions-8 | PASS | 1 row(s) = [180] | 1.938 | 2.504 | 4 |
| QM-count_statuses-1 | PASS | a={FAILED=20, NEW=180} b={NEW=180, FAILED=20} | 1.281 | 1.793 | 4 |
| QM-count_statuses-2 | PASS | a={FAILED=20, NEW=180} b={NEW=180, FAILED=20} | 0.855 | 2.053 | 4 |
| QM-count_statuses-3 | PASS | a={FAILED=20, NEW=180} b={FAILED=20, NEW=180} | 0.763 | 0.846 | 4 |
| QM-count_statuses-4 | PASS | a={FAILED=20, NEW=180} b={FAILED=20, NEW=180} | 0.809 | 1.177 | 4 |
| QM-count_statuses-5 | PASS | a={FAILED=20, NEW=180} b={NEW=180, FAILED=20} | 1.565 | 2.195 | 4 |
| QM-count_statuses-6 | PASS | a={FAILED=20, NEW=180} b={FAILED=20, NEW=180} | 0.785 | 0.856 | 4 |
| QM-keys_nonstrict-1 | PASS | 10 keys, same order | 3.691 | 4.478 | 4 |
| QM-keys_nonstrict-2 | PASS | 10 keys, same order | 3.674 | 6.044 | 4 |
| QM-keys_nonstrict-3 | PASS | 10 keys, same order | 4.707 | 5.621 | 4 |
| QM-keys_nonstrict-4 | PASS | 10 keys, same order | 2.924 | 4.105 | 4 |
| QM-keys_nonstrict-5 | PASS | 10 keys, same order | 3.437 | 4.137 | 4 |
| QM-keys_nonstrict-6 | PASS | 10 keys, same order | 3.775 | 5.643 | 4 |
| QM-keys_nonstrict-7 | PASS | 10 keys, same order | 2.528 | 3.662 | 4 |
| QM-keys_nonstrict-8 | PASS | 10 keys, same order | 4.343 | 5.469 | 4 |
| QM-keys_nonstrict-9 | PASS | 10 keys, same order | 3.623 | 5.013 | 4 |
| QM-keys_nonstrict-10 | PASS | 10 keys, same order | 3.674 | 4.684 | 4 |
| QM-keys_nonstrict-11 | PASS | 10 keys, same order | 3.334 | 4.674 | 4 |
| QM-keys_nonstrict-12 | PASS | 10 keys, same order | 3.567 | 5.370 | 4 |
| QM-keys_strict-1 | PASS | 10 keys, same order | 3.485 | 4.537 | 4 |
| QM-keys_strict-2 | PASS | 10 keys, same order | 3.744 | 5.123 | 4 |
| QM-keys_strict-3 | PASS | 10 keys, same order | 4.198 | 5.118 | 4 |
| QM-keys_strict-4 | PASS | 10 keys, same order | 2.801 | 4.182 | 4 |
| QM-keys_strict-5 | PASS | 10 keys, same order | 3.528 | 4.848 | 4 |
| QM-keys_strict-6 | PASS | 10 keys, same order | 2.534 | 3.779 | 4 |
| QM-keys_strict-7 | PASS | 10 keys, same order | 2.787 | 3.989 | 4 |
| QM-keys_strict-8 | PASS | 10 keys, same order | 4.370 | 5.479 | 4 |
| QM-keys_strict-9 | PASS | 10 keys, same order | 3.687 | 5.167 | 4 |
| QM-keys_strict-10 | PASS | 10 keys, same order | 3.581 | 4.542 | 4 |
| QM-oldest_created-1 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.861 | 3.603 | 4 |
| QM-oldest_created-2 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.838 | 3.407 | 4 |
| QM-oldest_created-3 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.163 | 2.593 | 4 |
| QM-oldest_created-4 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.179 | 2.619 | 4 |
| QM-oldest_created-5 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.280 | 2.617 | 4 |
| QM-oldest_due_nonstrict-1 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.121 | 4.122 | 4 |
| QM-oldest_due_nonstrict-2 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.097 | 3.853 | 4 |
| QM-oldest_due_nonstrict-3 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.240 | 2.776 | 4 |
| QM-oldest_due_nonstrict-4 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.242 | 3.215 | 4 |
| QM-oldest_due_nonstrict-5 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.281 | 4.037 | 4 |
| QM-oldest_due_strict-1 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 4.160 | 4.823 | 4 |
| QM-oldest_due_strict-2 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 4.325 | 4.876 | 4 |
| QM-oldest_due_strict-3 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.275 | 4.027 | 4 |
| QM-oldest_due_strict-4 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.109 | 3.787 | 4 |
| QM-oldest_due_strict-5 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.154 | 4.184 | 4 |
| QM-oldest_due_strict-6 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.021 | 4.120 | 4 |
| QM-oldest_due_strict-7 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 2.630 | 3.269 | 4 |
| QM-oldest_due_strict-8 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 4.103 | 4.901 | 4 |
| QM-oldest_due_strict-9 | PASS | 1 row(s) = [2026-01-01 06:33:20] | 3.154 | 4.210 | 4 |
| QM-pages-1 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.066 | 3.907 | 4 |
| QM-pages-2 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.135 | 2.935 | 4 |
| QM-pages-3 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 1.876 | 3.716 | 4 |
| QM-pages-4 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.418 | 3.395 | 4 |
| QM-pages-5 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.844 | 5.895 | 4 |
| QM-pages-6 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.276 | 3.888 | 4 |
| QM-pages-7 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.267 | 4.255 | 4 |
| QM-pages-8 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.298 | 3.554 | 4 |
| QM-pages-9 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 1.957 | 6.183 | 4 |
| QM-pages-10 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.807 | 5.911 | 4 |
| QM-pages-11 | PASS | 10 vs 10 rows over 10 keys; per-key id order equal | 2.504 | 4.470 | 4 |
