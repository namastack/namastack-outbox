# Noise (mariadb): `results/calibration/pass1` vs `results/calibration/pass2`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| mdb-main | HOT | 9 | 8% | 11% | S8-S4/keys_nonstrict (27557.17 vs 24817.88 ms) |
| mdb-main | WRITE | 12 | 31% | 42% | S8-S4/upd_retry (0.34 vs 0.49 ms) |
| mdb-main | MON | 21 | 2% | 8% | S8-S4/oldest_created (15214.24 vs 14051.85 ms) |
| mdb-opt | HOT | 9 | 7% | 9% | S8-S4/keys_nonstrict (0.92 vs 1.01 ms) |
| mdb-opt | WRITE | 12 | 22% | 60% | S8-S4/upd_retry (0.59 vs 0.37 ms) |
| mdb-opt | MON | 28 | 4% | 10% | S8-S2/count_partitions (0.47 vs 0.52 ms) |
| all | HOT | 18 | 8% | 11% | S8-S4/keys_nonstrict (27557.17 vs 24817.88 ms) |
| all | WRITE | 24 | 30% | 60% | S8-S4/upd_retry (0.59 vs 0.37 ms) |
| all | MON | 49 | 4% | 10% | S8-S2/count_partitions (0.47 vs 0.52 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| mdb-main | WRITE | 3 | 0.092 | 0.099 |
| mdb-main | MON | 3 | 0.032 | 0.039 |
| mdb-opt | WRITE | 3 | 0.154 | 0.168 |
| mdb-opt | MON | 2 | 0.006 | 0.006 |
| all | WRITE | 6 | 0.133 | 0.168 |
| all | MON | 5 | 0.026 | 0.039 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| mdb-main | S8-S2 | count_completed | MON | 188.680 | 186.294 | 1% |
| mdb-opt | S8-S2 | count_completed | MON | 204.494 | 203.172 | 1% |
| mdb-main | S8-S2 | count_failed | MON | 3.274 | 3.263 | 0% |
| mdb-opt | S8-S2 | count_failed | MON | 3.486 | 3.472 | 0% |
| mdb-main | S8-S2 | count_new | MON | 0.348 | 0.345 | 1% |
| mdb-opt | S8-S2 | count_new | MON | 0.368 | 0.384 | 4% |
| mdb-main | S8-S2 | count_partition_single | MON | 0.133 | 0.138 | 4% |
| mdb-opt | S8-S2 | count_partition_single | MON | 0.353 | 0.370 | 5% |
| mdb-main | S8-S2 | count_partitions | MON | 0.499 | 0.496 | 1% |
| mdb-opt | S8-S2 | count_partitions | MON | 0.474 | 0.521 | 10% |
| mdb-opt | S8-S2 | count_statuses | MON | 4.702 | 4.778 | 2% |
| mdb-opt | S8-S2 | count_statuses_all | MON | 254.056 | 256.917 | 1% |
| mdb-main | S8-S2 | delete_by_id | WRITE | 0.469 | 0.430 | 9% |
| mdb-opt | S8-S2 | delete_by_id | WRITE | 0.402 | 0.405 | 1% |
| mdb-main | S8-S2 | insert | WRITE | 0.655 | 0.716 | 9% |
| mdb-opt | S8-S2 | insert | WRITE | 0.554 | 0.515 | 8% |
| mdb-main | S8-S2 | keys_nonstrict | HOT | 1.634 | 1.662 | 2% |
| mdb-opt | S8-S2 | keys_nonstrict | HOT | 0.791 | 0.785 | 1% |
| mdb-main | S8-S2 | keys_strict | HOT | 0.929 | 0.995 | 7% |
| mdb-opt | S8-S2 | keys_strict | HOT | 0.780 | 0.774 | 1% |
| mdb-main | S8-S2 | oldest_created | MON | 0.952 | 0.972 | 2% |
| mdb-opt | S8-S2 | oldest_created | MON | 0.716 | 0.674 | 6% |
| mdb-main | S8-S2 | oldest_due_nonstrict | MON | 0.586 | 0.597 | 2% |
| mdb-opt | S8-S2 | oldest_due_nonstrict | MON | 0.871 | 0.862 | 1% |
| mdb-main | S8-S2 | oldest_due_strict | MON | 2.163 | 2.216 | 2% |
| mdb-opt | S8-S2 | oldest_due_strict | MON | 0.513 | 0.499 | 3% |
| mdb-main | S8-S2 | pages | HOT | 0.655 | 0.624 | 5% |
| mdb-opt | S8-S2 | pages | HOT | 0.513 | 0.490 | 5% |
| mdb-main | S8-S2 | upd_complete | WRITE | 0.491 | 0.575 | 17% |
| mdb-opt | S8-S2 | upd_complete | WRITE | 0.466 | 0.379 | 23% |
| mdb-main | S8-S2 | upd_fail | WRITE | 0.284 | 0.299 | 5% |
| mdb-opt | S8-S2 | upd_fail | WRITE | 0.380 | 0.282 | 35% |
| mdb-main | S8-S2 | upd_retry | WRITE | 0.259 | 0.358 | 38% |
| mdb-opt | S8-S2 | upd_retry | WRITE | 0.339 | 0.250 | 36% |
| mdb-main | S8-S4 | count_completed | MON | 189.078 | 189.088 | 0% |
| mdb-opt | S8-S4 | count_completed | MON | 206.143 | 205.305 | 0% |
| mdb-main | S8-S4 | count_failed | MON | 0.117 | 0.156 | 33% |
| mdb-opt | S8-S4 | count_failed | MON | 0.123 | 0.129 | 5% |
| mdb-main | S8-S4 | count_new | MON | 175.703 | 176.633 | 1% |
| mdb-opt | S8-S4 | count_new | MON | 189.937 | 190.772 | 0% |
| mdb-main | S8-S4 | count_partition_single | MON | 0.930 | 0.927 | 0% |
| mdb-opt | S8-S4 | count_partition_single | MON | 172.338 | 175.534 | 2% |
| mdb-main | S8-S4 | count_partitions | MON | 61.279 | 62.060 | 1% |
| mdb-opt | S8-S4 | count_partitions | MON | 198.027 | 196.591 | 1% |
| mdb-opt | S8-S4 | count_statuses | MON | 218.070 | 221.626 | 2% |
| mdb-opt | S8-S4 | count_statuses_all | MON | 482.133 | 485.107 | 1% |
| mdb-main | S8-S4 | delete_by_id | WRITE | 0.543 | 0.554 | 2% |
| mdb-opt | S8-S4 | delete_by_id | WRITE | 0.612 | 0.524 | 17% |
| mdb-main | S8-S4 | insert | WRITE | 0.692 | 0.920 | 33% |
| mdb-opt | S8-S4 | insert | WRITE | 0.595 | 0.612 | 3% |
| mdb-main | S8-S4 | keys_nonstrict | HOT | 27557.175 | 24817.877 | 11% |
| mdb-opt | S8-S4 | keys_nonstrict | HOT | 0.922 | 1.006 | 9% |
| mdb-main | S8-S4 | keys_strict | HOT | 1.105 | 1.187 | 7% |
| mdb-opt | S8-S4 | keys_strict | HOT | 0.848 | 0.907 | 7% |
| mdb-main | S8-S4 | oldest_created | MON | 15214.241 | 14051.846 | 8% |
| mdb-opt | S8-S4 | oldest_created | MON | 296.565 | 297.722 | 0% |
| mdb-main | S8-S4 | oldest_due_nonstrict | MON | 90.860 | 90.553 | 0% |
| mdb-opt | S8-S4 | oldest_due_nonstrict | MON | 278.051 | 281.456 | 1% |
| mdb-main | S8-S4 | oldest_due_strict | MON | 21951.585 | 22394.481 | 2% |
| mdb-opt | S8-S4 | oldest_due_strict | MON | 0.604 | 0.588 | 3% |
| mdb-main | S8-S4 | pages | HOT | 0.543 | 0.534 | 2% |
| mdb-opt | S8-S4 | pages | HOT | 0.534 | 0.536 | 0% |
| mdb-main | S8-S4 | upd_complete | WRITE | 0.873 | 0.957 | 10% |
| mdb-opt | S8-S4 | upd_complete | WRITE | 0.949 | 0.880 | 8% |
| mdb-main | S8-S4 | upd_fail | WRITE | 0.237 | 0.301 | 27% |
| mdb-opt | S8-S4 | upd_fail | WRITE | 0.417 | 0.249 | 67% |
| mdb-main | S8-S4 | upd_retry | WRITE | 0.342 | 0.487 | 42% |
| mdb-opt | S8-S4 | upd_retry | WRITE | 0.588 | 0.367 | 60% |
| mdb-main | S8-S5 | count_completed | MON | 57.495 | 57.325 | 0% |
| mdb-opt | S8-S5 | count_completed | MON | 62.052 | 62.120 | 0% |
| mdb-main | S8-S5 | count_failed | MON | 0.117 | 0.119 | 2% |
| mdb-opt | S8-S5 | count_failed | MON | 0.126 | 0.127 | 1% |
| mdb-main | S8-S5 | count_new | MON | 35.612 | 35.650 | 0% |
| mdb-opt | S8-S5 | count_new | MON | 38.505 | 38.390 | 0% |
| mdb-main | S8-S5 | count_partition_single | MON | 0.534 | 0.540 | 1% |
| mdb-opt | S8-S5 | count_partition_single | MON | 34.943 | 35.569 | 2% |
| mdb-main | S8-S5 | count_partitions | MON | 8.683 | 8.796 | 1% |
| mdb-opt | S8-S5 | count_partitions | MON | 39.551 | 39.176 | 1% |
| mdb-opt | S8-S5 | count_statuses | MON | 44.291 | 44.867 | 1% |
| mdb-opt | S8-S5 | count_statuses_all | MON | 122.571 | 122.632 | 0% |
| mdb-main | S8-S5 | delete_by_id | WRITE | 0.449 | 0.503 | 12% |
| mdb-opt | S8-S5 | delete_by_id | WRITE | 0.354 | 0.395 | 12% |
| mdb-main | S8-S5 | insert | WRITE | 0.716 | 0.630 | 14% |
| mdb-opt | S8-S5 | insert | WRITE | 0.602 | 0.540 | 11% |
| mdb-main | S8-S5 | keys_nonstrict | HOT | 2440.558 | 2431.280 | 0% |
| mdb-opt | S8-S5 | keys_nonstrict | HOT | 8.829 | 9.041 | 2% |
| mdb-main | S8-S5 | keys_strict | HOT | 14918.981 | 15349.666 | 3% |
| mdb-opt | S8-S5 | keys_strict | HOT | 197.104 | 196.045 | 1% |
| mdb-main | S8-S5 | oldest_created | MON | 2440.404 | 2361.277 | 3% |
| mdb-opt | S8-S5 | oldest_created | MON | 58.160 | 58.093 | 0% |
| mdb-main | S8-S5 | oldest_due_nonstrict | MON | 12.825 | 12.832 | 0% |
| mdb-opt | S8-S5 | oldest_due_nonstrict | MON | 56.601 | 57.125 | 1% |
| mdb-main | S8-S5 | oldest_due_strict | MON | 2605.610 | 2585.917 | 1% |
| mdb-opt | S8-S5 | oldest_due_strict | MON | 1.064 | 1.089 | 2% |
| mdb-main | S8-S5 | pages | HOT | 5080.068 | 4809.823 | 6% |
| mdb-opt | S8-S5 | pages | HOT | 1491.023 | 1459.506 | 2% |
| mdb-main | S8-S5 | upd_complete | WRITE | 0.668 | 0.734 | 10% |
| mdb-opt | S8-S5 | upd_complete | WRITE | 0.498 | 0.502 | 1% |
| mdb-main | S8-S5 | upd_fail | WRITE | 0.325 | 0.322 | 1% |
| mdb-opt | S8-S5 | upd_fail | WRITE | 0.325 | 0.330 | 2% |
| mdb-main | S8-S5 | upd_retry | WRITE | 0.351 | 0.346 | 1% |
| mdb-opt | S8-S5 | upd_retry | WRITE | 0.324 | 0.324 | 0% |
