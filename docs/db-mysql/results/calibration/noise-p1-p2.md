# Noise (mysql): `../results/calibration/cal-p1` vs `../results/calibration/cal-p2`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| my-main | HOT | 9 | 8% | 14% | S8-S5/keys_strict (47473.73 vs 54258.07 ms) |
| my-main | WRITE | 15 | 88% | 156% | S8-S2/upd_fail (2.41 vs 0.94 ms) |
| my-main | MON | 22 | 7% | 151% | S8-S2/count_new (1.35 vs 0.54 ms) |
| my-opt | HOT | 9 | 8% | 15% | S8-S4/pages (1.59 vs 1.39 ms) |
| my-opt | WRITE | 15 | 98% | 136% | S8-S5/upd_retry (2.05 vs 0.87 ms) |
| my-opt | MON | 28 | 10% | 31% | S8-S2/count_partition_single (0.78 vs 0.59 ms) |
| all | HOT | 18 | 9% | 15% | S8-S4/pages (1.59 vs 1.39 ms) |
| all | WRITE | 30 | 104% | 156% | S8-S2/upd_fail (2.41 vs 0.94 ms) |
| all | MON | 50 | 8% | 151% | S8-S2/count_new (1.35 vs 0.54 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| my-main | MON | 2 | 0.058 | 0.062 |
| my-opt | MON | 2 | 0.062 | 0.066 |
| all | MON | 4 | 0.065 | 0.066 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| my-main | S8-S2 | count_completed | MON | 169.680 | 178.066 | 5% |
| my-opt | S8-S2 | count_completed | MON | 173.472 | 174.290 | 0% |
| my-main | S8-S2 | count_failed | MON | 2.466 | 2.528 | 3% |
| my-opt | S8-S2 | count_failed | MON | 2.838 | 2.634 | 8% |
| my-main | S8-S2 | count_new | MON | 1.346 | 0.537 | 151% |
| my-opt | S8-S2 | count_new | MON | 0.530 | 0.612 | 15% |
| my-main | S8-S2 | count_partition_single | MON | 0.343 | 0.281 | 22% |
| my-opt | S8-S2 | count_partition_single | MON | 0.776 | 0.593 | 31% |
| my-main | S8-S2 | count_partitions | MON | 1.018 | 0.961 | 6% |
| my-opt | S8-S2 | count_partitions | MON | 0.896 | 0.851 | 5% |
| my-opt | S8-S2 | count_statuses | MON | 6.208 | 7.104 | 14% |
| my-opt | S8-S2 | count_statuses_all | MON | 494.838 | 495.206 | 0% |
| my-main | S8-S2 | delete_by_id | WRITE | 2.638 | 1.300 | 103% |
| my-opt | S8-S2 | delete_by_id | WRITE | 1.529 | 1.006 | 52% |
| my-main | S8-S2 | insert | WRITE | 1.623 | 2.430 | 50% |
| my-opt | S8-S2 | insert | WRITE | 1.469 | 1.254 | 17% |
| my-main | S8-S2 | keys_nonstrict | HOT | 2.500 | 2.509 | 0% |
| my-opt | S8-S2 | keys_nonstrict | HOT | 3.159 | 3.042 | 4% |
| my-main | S8-S2 | keys_strict | HOT | 3.074 | 3.076 | 0% |
| my-opt | S8-S2 | keys_strict | HOT | 3.363 | 3.176 | 6% |
| my-main | S8-S2 | oldest_created | MON | 2.346 | 2.529 | 8% |
| my-opt | S8-S2 | oldest_created | MON | 2.136 | 2.167 | 1% |
| my-main | S8-S2 | oldest_due_nonstrict | MON | 1.053 | 1.023 | 3% |
| my-opt | S8-S2 | oldest_due_nonstrict | MON | 1.210 | 1.180 | 3% |
| my-main | S8-S2 | oldest_due_strict | MON | 3.751 | 3.711 | 1% |
| my-opt | S8-S2 | oldest_due_strict | MON | 2.477 | 2.450 | 1% |
| my-main | S8-S2 | pages | HOT | 1.807 | 1.930 | 7% |
| my-opt | S8-S2 | pages | HOT | 1.444 | 1.534 | 6% |
| my-main | S8-S2 | upd_complete | WRITE | 1.917 | 1.557 | 23% |
| my-opt | S8-S2 | upd_complete | WRITE | 1.085 | 1.439 | 33% |
| my-main | S8-S2 | upd_fail | WRITE | 2.414 | 0.943 | 156% |
| my-opt | S8-S2 | upd_fail | WRITE | 1.370 | 1.646 | 20% |
| my-main | S8-S2 | upd_retry | WRITE | 1.688 | 1.316 | 28% |
| my-opt | S8-S2 | upd_retry | WRITE | 0.984 | 1.222 | 24% |
| my-main | S8-S4 | count_completed | MON | 170.606 | 175.268 | 3% |
| my-opt | S8-S4 | count_completed | MON | 1711.799 | 1840.359 | 8% |
| my-main | S8-S4 | count_failed | MON | 0.364 | 0.339 | 7% |
| my-opt | S8-S4 | count_failed | MON | 0.354 | 0.288 | 23% |
| my-main | S8-S4 | count_new | MON | 137.156 | 142.580 | 4% |
| my-opt | S8-S4 | count_new | MON | 147.460 | 141.036 | 5% |
| my-main | S8-S4 | count_partition_single | MON | 1.093 | 1.088 | 0% |
| my-opt | S8-S4 | count_partition_single | MON | 178.939 | 180.980 | 1% |
| my-main | S8-S4 | count_partitions | MON | 76.622 | 78.571 | 3% |
| my-opt | S8-S4 | count_partitions | MON | 199.250 | 198.893 | 0% |
| my-opt | S8-S4 | count_statuses | MON | 327.345 | 321.516 | 2% |
| my-opt | S8-S4 | count_statuses_all | MON | 3074.200 | 3138.316 | 2% |
| my-main | S8-S4 | delete_by_id | WRITE | 1.707 | 2.593 | 52% |
| my-opt | S8-S4 | delete_by_id | WRITE | 2.348 | 2.836 | 21% |
| my-main | S8-S4 | insert | WRITE | 1.695 | 1.903 | 12% |
| my-opt | S8-S4 | insert | WRITE | 1.777 | 1.841 | 4% |
| my-main | S8-S4 | keys_nonstrict | HOT | 257001.810 | 244301.424 | 5% |
| my-opt | S8-S4 | keys_nonstrict | HOT | 2.056 | 1.962 | 5% |
| my-main | S8-S4 | keys_strict | HOT | 2.461 | 2.437 | 1% |
| my-opt | S8-S4 | keys_strict | HOT | 2.206 | 2.341 | 6% |
| my-main | S8-S4 | oldest_created | MON | 285475.929 | 282897.386 | 1% |
| my-opt | S8-S4 | oldest_created | MON | 75796.746 | 73970.256 | 2% |
| my-main | S8-S4 | oldest_due_nonstrict | MON | 87.596 | 91.421 | 4% |
| my-opt | S8-S4 | oldest_due_nonstrict | MON | 315.716 | 326.910 | 4% |
| my-main | S8-S4 | oldest_due_strict | MON | 359144.358 | 336258.957 | 7% |
| my-opt | S8-S4 | oldest_due_strict | MON | 285121.473 | 288301.931 | 1% |
| my-main | S8-S4 | pages | HOT | 1.831 | 1.711 | 7% |
| my-opt | S8-S4 | pages | HOT | 1.594 | 1.388 | 15% |
| my-main | S8-S4 | upd_complete | WRITE | 2.894 | 2.379 | 22% |
| my-opt | S8-S4 | upd_complete | WRITE | 1.876 | 2.885 | 54% |
| my-main | S8-S4 | upd_fail | WRITE | 1.568 | 2.613 | 67% |
| my-opt | S8-S4 | upd_fail | WRITE | 2.126 | 1.010 | 110% |
| my-main | S8-S4 | upd_retry | WRITE | 1.124 | 1.045 | 8% |
| my-opt | S8-S4 | upd_retry | WRITE | 1.166 | 1.057 | 10% |
| my-main | S8-S5 | count_completed | MON | 51.993 | 52.324 | 1% |
| my-opt | S8-S5 | count_completed | MON | 53.285 | 53.102 | 0% |
| my-main | S8-S5 | count_failed | MON | 0.273 | 0.293 | 7% |
| my-opt | S8-S5 | count_failed | MON | 0.302 | 0.277 | 9% |
| my-main | S8-S5 | count_new | MON | 28.448 | 28.705 | 1% |
| my-opt | S8-S5 | count_new | MON | 29.325 | 28.951 | 1% |
| my-main | S8-S5 | count_partition_single | MON | 0.758 | 0.747 | 1% |
| my-opt | S8-S5 | count_partition_single | MON | 36.795 | 36.641 | 0% |
| my-main | S8-S5 | count_partitions | MON | 11.203 | 11.297 | 1% |
| my-opt | S8-S5 | count_partitions | MON | 42.128 | 41.742 | 1% |
| my-opt | S8-S5 | count_statuses | MON | 65.474 | 65.621 | 0% |
| my-opt | S8-S5 | count_statuses_all | MON | 142.606 | 144.985 | 2% |
| my-main | S8-S5 | delete_by_id | WRITE | 1.363 | 2.007 | 47% |
| my-opt | S8-S5 | delete_by_id | WRITE | 2.338 | 1.702 | 37% |
| my-main | S8-S5 | insert | WRITE | 1.898 | 1.858 | 2% |
| my-opt | S8-S5 | insert | WRITE | 1.358 | 1.528 | 13% |
| my-main | S8-S5 | keys_nonstrict | HOT | 8570.435 | 8671.712 | 1% |
| my-opt | S8-S5 | keys_nonstrict | HOT | 4848.015 | 4802.970 | 1% |
| my-main | S8-S5 | keys_strict | HOT | 47473.732 | 54258.074 | 14% |
| my-opt | S8-S5 | keys_strict | HOT | 331.863 | 332.969 | 0% |
| my-main | S8-S5 | oldest_created | MON | 7848.749 | 7895.745 | 1% |
| my-opt | S8-S5 | oldest_created | MON | 8209.211 | 8249.697 | 0% |
| my-main | S8-S5 | oldest_due_nonstrict | MON | 12.901 | 12.992 | 1% |
| my-opt | S8-S5 | oldest_due_nonstrict | MON | 7818.454 | 7573.687 | 3% |
| my-main | S8-S5 | oldest_due_strict | MON | 8463.595 | 8356.982 | 1% |
| my-opt | S8-S5 | oldest_due_strict | MON | 72926.481 | 72064.495 | 1% |
| my-main | S8-S5 | pages | HOT | 53288.749 | 54912.592 | 3% |
| my-opt | S8-S5 | pages | HOT | 4232.715 | 4273.810 | 1% |
| my-main | S8-S5 | upd_complete | WRITE | 1.337 | 1.338 | 0% |
| my-opt | S8-S5 | upd_complete | WRITE | 1.476 | 2.642 | 79% |
| my-main | S8-S5 | upd_fail | WRITE | 0.822 | 0.928 | 13% |
| my-opt | S8-S5 | upd_fail | WRITE | 0.807 | 0.913 | 13% |
| my-main | S8-S5 | upd_retry | WRITE | 0.867 | 0.950 | 10% |
| my-opt | S8-S5 | upd_retry | WRITE | 2.053 | 0.870 | 136% |
