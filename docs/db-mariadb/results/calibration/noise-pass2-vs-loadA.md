# Noise (mariadb): `pass2` vs `loadA`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| mdb-main | HOT | 6 | 6% | 6% | S8-S4/keys_strict (1.19 vs 1.12 ms) |
| mdb-main | WRITE | 8 | 79% | 170% | S8-S2/delete_by_id (0.43 vs 1.16 ms) |
| mdb-main | MON | 14 | 85% | 131% | S8-S4/oldest_created (14051.85 vs 32501.54 ms) |
| mdb-opt | HOT | 6 | 16% | 18% | S8-S4/keys_nonstrict (1.01 vs 0.85 ms) |
| mdb-opt | WRITE | 7 | 35% | 37% | S8-S2/upd_complete (0.38 vs 0.52 ms) |
| mdb-opt | MON | 19 | 71% | 77% | S8-S4/oldest_due_nonstrict (281.46 vs 159.01 ms) |
| all | HOT | 12 | 14% | 18% | S8-S4/keys_nonstrict (1.01 vs 0.85 ms) |
| all | WRITE | 15 | 38% | 170% | S8-S2/delete_by_id (0.43 vs 1.16 ms) |
| all | MON | 33 | 72% | 131% | S8-S4/oldest_created (14051.85 vs 32501.54 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| mdb-main | WRITE | 2 | 0.102 | 0.107 |
| mdb-main | MON | 2 | 0.035 | 0.039 |
| mdb-opt | WRITE | 3 | 0.154 | 0.155 |
| mdb-opt | MON | 1 | 0.016 | 0.016 |
| all | WRITE | 5 | 0.153 | 0.155 |
| all | MON | 3 | 0.034 | 0.039 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| mdb-main | S8-S2 | count_completed | MON | 186.294 | 187.095 | 0% |
| mdb-opt | S8-S2 | count_completed | MON | 203.172 | 205.482 | 1% |
| mdb-main | S8-S2 | count_failed | MON | 3.263 | 3.311 | 1% |
| mdb-opt | S8-S2 | count_failed | MON | 3.472 | 3.500 | 1% |
| mdb-main | S8-S2 | count_new | MON | 0.345 | 0.349 | 1% |
| mdb-opt | S8-S2 | count_new | MON | 0.384 | 0.371 | 4% |
| mdb-main | S8-S2 | count_partition_single | MON | 0.138 | 0.137 | 1% |
| mdb-opt | S8-S2 | count_partition_single | MON | 0.370 | 0.354 | 5% |
| mdb-main | S8-S2 | count_partitions | MON | 0.496 | 0.501 | 1% |
| mdb-opt | S8-S2 | count_partitions | MON | 0.521 | 0.468 | 11% |
| mdb-opt | S8-S2 | count_statuses | MON | 4.778 | 4.783 | 0% |
| mdb-opt | S8-S2 | count_statuses_all | MON | 256.917 | 257.090 | 0% |
| mdb-main | S8-S2 | delete_by_id | WRITE | 0.430 | 1.163 | 170% |
| mdb-opt | S8-S2 | delete_by_id | WRITE | 0.405 | 0.415 | 2% |
| mdb-main | S8-S2 | insert | WRITE | 0.716 | 0.819 | 14% |
| mdb-opt | S8-S2 | insert | WRITE | 0.515 | 0.631 | 23% |
| mdb-main | S8-S2 | keys_nonstrict | HOT | 1.662 | 1.709 | 3% |
| mdb-opt | S8-S2 | keys_nonstrict | HOT | 0.785 | 0.775 | 1% |
| mdb-main | S8-S2 | keys_strict | HOT | 0.995 | 0.952 | 5% |
| mdb-opt | S8-S2 | keys_strict | HOT | 0.774 | 0.754 | 3% |
| mdb-main | S8-S2 | oldest_created | MON | 0.972 | 0.949 | 2% |
| mdb-opt | S8-S2 | oldest_created | MON | 0.674 | 0.705 | 5% |
| mdb-main | S8-S2 | oldest_due_nonstrict | MON | 0.597 | 0.581 | 3% |
| mdb-opt | S8-S2 | oldest_due_nonstrict | MON | 0.862 | 0.865 | 0% |
| mdb-main | S8-S2 | oldest_due_strict | MON | 2.216 | 2.215 | 0% |
| mdb-opt | S8-S2 | oldest_due_strict | MON | 0.499 | 0.519 | 4% |
| mdb-main | S8-S2 | pages | HOT | 0.624 | 0.617 | 1% |
| mdb-opt | S8-S2 | pages | HOT | 0.490 | 0.495 | 1% |
| mdb-main | S8-S2 | upd_complete | WRITE | 0.575 | 0.643 | 12% |
| mdb-opt | S8-S2 | upd_complete | WRITE | 0.379 | 0.520 | 37% |
| mdb-main | S8-S2 | upd_fail | WRITE | 0.299 | 0.406 | 36% |
| mdb-opt | S8-S2 | upd_fail | WRITE | 0.282 | 0.437 | 55% |
| mdb-main | S8-S2 | upd_retry | WRITE | 0.358 | 0.421 | 18% |
| mdb-opt | S8-S2 | upd_retry | WRITE | 0.250 | 0.401 | 60% |
| mdb-main | S8-S4 | count_completed | MON | 189.088 | 186.222 | 2% |
| mdb-opt | S8-S4 | count_completed | MON | 205.305 | 122.218 | 68% |
| mdb-main | S8-S4 | count_failed | MON | 0.156 | 0.117 | 33% |
| mdb-opt | S8-S4 | count_failed | MON | 0.129 | 0.113 | 14% |
| mdb-main | S8-S4 | count_new | MON | 176.633 | 174.235 | 1% |
| mdb-opt | S8-S4 | count_new | MON | 190.772 | 112.410 | 70% |
| mdb-main | S8-S4 | count_partition_single | MON | 0.927 | 0.934 | 1% |
| mdb-opt | S8-S4 | count_partition_single | MON | 175.534 | 102.521 | 71% |
| mdb-main | S8-S4 | count_partitions | MON | 62.060 | 61.112 | 2% |
| mdb-opt | S8-S4 | count_partitions | MON | 196.591 | 117.210 | 68% |
| mdb-opt | S8-S4 | count_statuses | MON | 221.626 | 128.803 | 72% |
| mdb-opt | S8-S4 | count_statuses_all | MON | 485.107 | 284.634 | 70% |
| mdb-main | S8-S4 | delete_by_id | WRITE | 0.554 | 0.554 | 0% |
| mdb-opt | S8-S4 | delete_by_id | WRITE | 0.524 | 0.618 | 18% |
| mdb-main | S8-S4 | insert | WRITE | 0.920 | 0.661 | 39% |
| mdb-opt | S8-S4 | insert | WRITE | 0.612 | 0.602 | 2% |
| mdb-main | S8-S4 | keys_nonstrict | HOT | 24817.877 | 23482.614 | 6% |
| mdb-opt | S8-S4 | keys_nonstrict | HOT | 1.006 | 0.849 | 18% |
| mdb-main | S8-S4 | keys_strict | HOT | 1.187 | 1.120 | 6% |
| mdb-opt | S8-S4 | keys_strict | HOT | 0.907 | 0.795 | 14% |
| mdb-main | S8-S4 | oldest_created | MON | 14051.846 | 32501.545 | 131% |
| mdb-opt | S8-S4 | oldest_created | MON | 297.722 | 179.126 | 66% |
| mdb-main | S8-S4 | oldest_due_nonstrict | MON | 90.553 | 90.330 | 0% |
| mdb-opt | S8-S4 | oldest_due_nonstrict | MON | 281.456 | 159.013 | 77% |
| mdb-main | S8-S4 | oldest_due_strict | MON | 22394.481 | 49243.867 | 120% |
| mdb-opt | S8-S4 | oldest_due_strict | MON | 0.588 | 0.550 | 7% |
| mdb-main | S8-S4 | pages | HOT | 0.534 | 0.532 | 0% |
| mdb-opt | S8-S4 | pages | HOT | 0.536 | 0.490 | 9% |
| mdb-main | S8-S4 | upd_complete | WRITE | 0.957 | 0.916 | 4% |
| mdb-opt | S8-S4 | upd_complete | WRITE | 0.880 | 0.916 | 4% |
| mdb-main | S8-S4 | upd_fail | WRITE | 0.301 | 0.240 | 25% |
| mdb-opt | S8-S4 | upd_fail | WRITE | 0.249 | 0.254 | 2% |
| mdb-main | S8-S4 | upd_retry | WRITE | 0.487 | 0.366 | 33% |
| mdb-opt | S8-S4 | upd_retry | WRITE | 0.367 | 0.491 | 34% |
