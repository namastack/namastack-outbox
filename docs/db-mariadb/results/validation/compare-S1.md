Baseline: `results/validation/mdb-main/mariadb/S1`  
Candidate: `results/validation/mdb-opt/mariadb/S1`  
Metric: median (ms), relative change = (candidate - baseline) / baseline

Plan mode = custom/generic plans of the timed reps (plancache.csv); cells whose modes differ are flagged `MODE DIFFERS` and are not comparable.

| query | base median | cand median | change | base p95 | cand p95 | change | rows base | rows cand | mode base | mode cand | note |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| keys_strict | 1.235 | 0.616 | -50% | 1.257 | 0.635 | -49% | 10 | 10 |  |  |  |
| keys_nonstrict | 1.415 | 0.625 | -56% | 1.757 | 0.661 | -62% | 10 | 10 |  |  |  |
| pages | 0.487 | 0.292 | -40% | 0.573 | 0.370 | -35% | 10 | 10 |  |  |  |
| insert | 0.268 | 0.177 | -34% | 0.648 | 0.224 | -65% | 1 | 1 |  |  |  |
| upd_complete | 0.215 | 0.154 | -28% | 0.240 | 0.167 | -30% | 1 | 1 |  |  |  |
| upd_retry | 0.169 | 0.161 | ~0 | 0.186 | 0.178 | ~0 | 1 | 1 |  |  |  |
| upd_fail | 0.157 | 0.149 | ~0 | 0.198 | 0.177 | ~0 | 1 | 1 |  |  |  |
| delete_by_id | 0.081 | 0.100 | ~0 | 0.095 | 0.119 | ~0 | 1 | 1 |  |  |  |
| count_new | 0.110 | 0.150 | ~0 | 0.142 | 0.173 | ~0 | 1 | 1 |  |  |  |
| count_failed | 0.082 | 0.110 | ~0 | 0.096 | 0.118 | ~0 | 1 | 1 |  |  |  |
| count_completed | 1.525 | 1.362 | -11% | 1.539 | 1.378 | -10% | 1 | 1 |  |  |  |
| count_statuses | n/a | 0.119 | n/a | n/a | 0.131 | n/a |  | 2 |  |  | n/a |
| count_statuses_all | n/a | 1.453 | n/a | n/a | 2.785 | n/a |  | 3 |  |  | n/a |
| count_partitions | 0.383 | 0.300 | -22% | 0.479 | 0.327 | -32% | 1 | 1 |  |  |  |
| count_partition_single | 0.081 | 0.113 | ~0 | 0.089 | 0.120 | ~0 | 1 | 1 |  |  |  |
| oldest_created | 0.391 | 0.325 | -17% | 0.409 | 0.339 | -17% | 1 | 1 |  |  |  |
| oldest_due_strict | 1.109 | 0.437 | -61% | 1.623 | 0.626 | -61% | 1 | 1 |  |  |  |
| oldest_due_nonstrict | 0.730 | 0.740 | ~0 | 1.438 | 0.762 | -47% | 1 | 1 |  |  |  |
| find_completed_before | 11.675 | 3.520 | -70% | 16.100 | 4.173 | -74% | 1000 | 1000 |  |  |  |
| find_failed_before | 0.111 | 0.132 | ~0 | 0.125 | 0.145 | ~0 | 0 | 0 |  |  |  |
| ids_completed_before | 9.361 | 0.494 | -95% | 9.424 | 0.551 | -94% | 1000 | 1000 |  |  |  |
| ids_failed_before | 0.095 | 0.121 | ~0 | 0.116 | 0.133 | ~0 | 0 | 0 |  |  |  |
| delete_by_ids | 9.189 | 7.460 | -19% | 9.758 | 7.919 | -19% | 1000 | 1000 |  |  |  |
| delete_by_status_failed | 0.238 | 0.181 | -24% | 0.259 | 0.191 | -26% | 20 | 20 |  |  |  |
| delete_by_key_status | 0.089 | 0.081 | ~0 | 0.100 | 0.086 | ~0 | 1 | 1 |  |  |  |
