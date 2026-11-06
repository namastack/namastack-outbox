# Noise (mysql): `../results/calibration/cal-p2` vs `../results/calibration/cal-bg`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| my-main | HOT | 3 | 7% | 7% | S8-S2/pages (1.93 vs 1.80 ms) |
| my-main | WRITE | 5 | 63% | 78% | S8-S2/upd_fail (0.94 vs 1.68 ms) |
| my-main | MON | 7 | 28% | 42% | S8-S2/count_partitions (0.96 vs 1.36 ms) |
| my-opt | HOT | 3 | 4% | 4% | S8-S2/keys_nonstrict (3.04 vs 3.17 ms) |
| my-opt | WRITE | 5 | 77% | 94% | S8-S2/delete_by_id (1.01 vs 1.95 ms) |
| my-opt | MON | 10 | 40% | 56% | S8-S2/count_new (0.61 vs 0.96 ms) |
| all | HOT | 6 | 6% | 7% | S8-S2/pages (1.93 vs 1.80 ms) |
| all | WRITE | 10 | 79% | 94% | S8-S2/delete_by_id (1.01 vs 1.95 ms) |
| all | MON | 17 | 40% | 56% | S8-S2/count_new (0.61 vs 0.96 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| my-main | MON | 1 | 0.106 | 0.106 |
| all | MON | 1 | 0.106 | 0.106 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| my-main | S8-S2 | count_completed | MON | 178.066 | 180.389 | 1% |
| my-opt | S8-S2 | count_completed | MON | 174.290 | 184.012 | 6% |
| my-main | S8-S2 | count_failed | MON | 2.528 | 2.805 | 11% |
| my-opt | S8-S2 | count_failed | MON | 2.634 | 2.520 | 5% |
| my-main | S8-S2 | count_new | MON | 0.537 | 0.617 | 15% |
| my-opt | S8-S2 | count_new | MON | 0.612 | 0.956 | 56% |
| my-main | S8-S2 | count_partition_single | MON | 0.281 | 0.387 | 38% |
| my-opt | S8-S2 | count_partition_single | MON | 0.593 | 0.590 | 1% |
| my-main | S8-S2 | count_partitions | MON | 0.961 | 1.365 | 42% |
| my-opt | S8-S2 | count_partitions | MON | 0.851 | 0.934 | 10% |
| my-opt | S8-S2 | count_statuses | MON | 7.104 | 6.476 | 10% |
| my-opt | S8-S2 | count_statuses_all | MON | 495.206 | 508.458 | 3% |
| my-main | S8-S2 | delete_by_id | WRITE | 1.300 | 1.279 | 2% |
| my-opt | S8-S2 | delete_by_id | WRITE | 1.006 | 1.948 | 94% |
| my-main | S8-S2 | insert | WRITE | 2.430 | 3.119 | 28% |
| my-opt | S8-S2 | insert | WRITE | 1.254 | 1.332 | 6% |
| my-main | S8-S2 | keys_nonstrict | HOT | 2.509 | 2.632 | 5% |
| my-opt | S8-S2 | keys_nonstrict | HOT | 3.042 | 3.174 | 4% |
| my-main | S8-S2 | keys_strict | HOT | 3.076 | 3.087 | 0% |
| my-opt | S8-S2 | keys_strict | HOT | 3.176 | 3.210 | 1% |
| my-main | S8-S2 | oldest_created | MON | 2.529 | 2.828 | 12% |
| my-opt | S8-S2 | oldest_created | MON | 2.167 | 2.990 | 38% |
| my-main | S8-S2 | oldest_due_nonstrict | MON | 1.023 | 1.218 | 19% |
| my-opt | S8-S2 | oldest_due_nonstrict | MON | 1.180 | 1.223 | 4% |
| my-main | S8-S2 | oldest_due_strict | MON | 3.711 | 4.431 | 19% |
| my-opt | S8-S2 | oldest_due_strict | MON | 2.450 | 2.457 | 0% |
| my-main | S8-S2 | pages | HOT | 1.930 | 1.799 | 7% |
| my-opt | S8-S2 | pages | HOT | 1.534 | 1.515 | 1% |
| my-main | S8-S2 | upd_complete | WRITE | 1.557 | 1.706 | 10% |
| my-opt | S8-S2 | upd_complete | WRITE | 1.439 | 1.909 | 33% |
| my-main | S8-S2 | upd_fail | WRITE | 0.943 | 1.677 | 78% |
| my-opt | S8-S2 | upd_fail | WRITE | 1.646 | 1.628 | 1% |
| my-main | S8-S2 | upd_retry | WRITE | 1.316 | 0.940 | 40% |
| my-opt | S8-S2 | upd_retry | WRITE | 1.222 | 0.802 | 52% |
