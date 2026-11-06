# Noise (mysql): `../results/calibration/cal-p1` vs `../results/calibration/cal-bg`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| my-main | HOT | 3 | 4% | 5% | S8-S2/keys_nonstrict (2.50 vs 2.63 ms) |
| my-main | WRITE | 5 | 101% | 106% | S8-S2/delete_by_id (2.64 vs 1.28 ms) |
| my-main | MON | 8 | 59% | 118% | S8-S2/count_new (1.35 vs 0.62 ms) |
| my-opt | HOT | 3 | 5% | 5% | S8-S2/pages (1.44 vs 1.51 ms) |
| my-opt | WRITE | 5 | 57% | 76% | S8-S2/upd_complete (1.08 vs 1.91 ms) |
| my-opt | MON | 10 | 44% | 80% | S8-S2/count_new (0.53 vs 0.96 ms) |
| all | HOT | 6 | 5% | 5% | S8-S2/keys_nonstrict (2.50 vs 2.63 ms) |
| all | WRITE | 10 | 94% | 106% | S8-S2/delete_by_id (2.64 vs 1.28 ms) |
| all | MON | 18 | 52% | 118% | S8-S2/count_new (1.35 vs 0.62 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| my-main | S8-S2 | count_completed | MON | 169.680 | 180.389 | 6% |
| my-opt | S8-S2 | count_completed | MON | 173.472 | 184.012 | 6% |
| my-main | S8-S2 | count_failed | MON | 2.466 | 2.805 | 14% |
| my-opt | S8-S2 | count_failed | MON | 2.838 | 2.520 | 13% |
| my-main | S8-S2 | count_new | MON | 1.346 | 0.617 | 118% |
| my-opt | S8-S2 | count_new | MON | 0.530 | 0.956 | 80% |
| my-main | S8-S2 | count_partition_single | MON | 0.343 | 0.387 | 13% |
| my-opt | S8-S2 | count_partition_single | MON | 0.776 | 0.590 | 32% |
| my-main | S8-S2 | count_partitions | MON | 1.018 | 1.365 | 34% |
| my-opt | S8-S2 | count_partitions | MON | 0.896 | 0.934 | 4% |
| my-opt | S8-S2 | count_statuses | MON | 6.208 | 6.476 | 4% |
| my-opt | S8-S2 | count_statuses_all | MON | 494.838 | 508.458 | 3% |
| my-main | S8-S2 | delete_by_id | WRITE | 2.638 | 1.279 | 106% |
| my-opt | S8-S2 | delete_by_id | WRITE | 1.529 | 1.948 | 27% |
| my-main | S8-S2 | insert | WRITE | 1.623 | 3.119 | 92% |
| my-opt | S8-S2 | insert | WRITE | 1.469 | 1.332 | 10% |
| my-main | S8-S2 | keys_nonstrict | HOT | 2.500 | 2.632 | 5% |
| my-opt | S8-S2 | keys_nonstrict | HOT | 3.159 | 3.174 | 0% |
| my-main | S8-S2 | keys_strict | HOT | 3.074 | 3.087 | 0% |
| my-opt | S8-S2 | keys_strict | HOT | 3.363 | 3.210 | 5% |
| my-main | S8-S2 | oldest_created | MON | 2.346 | 2.828 | 21% |
| my-opt | S8-S2 | oldest_created | MON | 2.136 | 2.990 | 40% |
| my-main | S8-S2 | oldest_due_nonstrict | MON | 1.053 | 1.218 | 16% |
| my-opt | S8-S2 | oldest_due_nonstrict | MON | 1.210 | 1.223 | 1% |
| my-main | S8-S2 | oldest_due_strict | MON | 3.751 | 4.431 | 18% |
| my-opt | S8-S2 | oldest_due_strict | MON | 2.477 | 2.457 | 1% |
| my-main | S8-S2 | pages | HOT | 1.807 | 1.799 | 0% |
| my-opt | S8-S2 | pages | HOT | 1.444 | 1.515 | 5% |
| my-main | S8-S2 | upd_complete | WRITE | 1.917 | 1.706 | 12% |
| my-opt | S8-S2 | upd_complete | WRITE | 1.085 | 1.909 | 76% |
| my-main | S8-S2 | upd_fail | WRITE | 2.414 | 1.677 | 44% |
| my-opt | S8-S2 | upd_fail | WRITE | 1.370 | 1.628 | 19% |
| my-main | S8-S2 | upd_retry | WRITE | 1.688 | 0.940 | 80% |
| my-opt | S8-S2 | upd_retry | WRITE | 0.984 | 0.802 | 23% |
