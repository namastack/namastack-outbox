# Noise (mariadb): `pass1` vs `loadA`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| mdb-main | HOT | 6 | 12% | 17% | S8-S4/keys_nonstrict (27557.17 vs 23482.61 ms) |
| mdb-main | WRITE | 7 | 78% | 148% | S8-S2/delete_by_id (0.47 vs 1.16 ms) |
| mdb-main | MON | 14 | 80% | 124% | S8-S4/oldest_due_strict (21951.58 vs 49243.87 ms) |
| mdb-opt | HOT | 6 | 9% | 9% | S8-S4/pages (0.53 vs 0.49 ms) |
| mdb-opt | WRITE | 9 | 19% | 20% | S8-S4/upd_retry (0.59 vs 0.49 ms) |
| mdb-opt | MON | 19 | 69% | 75% | S8-S4/oldest_due_nonstrict (278.05 vs 159.01 ms) |
| all | HOT | 12 | 9% | 17% | S8-S4/keys_nonstrict (27557.17 vs 23482.61 ms) |
| all | WRITE | 16 | 28% | 148% | S8-S2/delete_by_id (0.47 vs 1.16 ms) |
| all | MON | 33 | 69% | 124% | S8-S4/oldest_due_strict (21951.58 vs 49243.87 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| mdb-main | WRITE | 3 | 0.154 | 0.162 |
| mdb-main | MON | 2 | 0.004 | 0.004 |
| mdb-opt | WRITE | 1 | 0.163 | 0.163 |
| mdb-opt | MON | 1 | 0.010 | 0.010 |
| all | WRITE | 4 | 0.163 | 0.163 |
| all | MON | 3 | 0.009 | 0.010 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| mdb-main | S8-S2 | count_completed | MON | 188.680 | 187.095 | 1% |
| mdb-opt | S8-S2 | count_completed | MON | 204.494 | 205.482 | 0% |
| mdb-main | S8-S2 | count_failed | MON | 3.274 | 3.311 | 1% |
| mdb-opt | S8-S2 | count_failed | MON | 3.486 | 3.500 | 0% |
| mdb-main | S8-S2 | count_new | MON | 0.348 | 0.349 | 0% |
| mdb-opt | S8-S2 | count_new | MON | 0.368 | 0.371 | 1% |
| mdb-main | S8-S2 | count_partition_single | MON | 0.133 | 0.137 | 3% |
| mdb-opt | S8-S2 | count_partition_single | MON | 0.353 | 0.354 | 0% |
| mdb-main | S8-S2 | count_partitions | MON | 0.499 | 0.501 | 0% |
| mdb-opt | S8-S2 | count_partitions | MON | 0.474 | 0.468 | 1% |
| mdb-opt | S8-S2 | count_statuses | MON | 4.702 | 4.783 | 2% |
| mdb-opt | S8-S2 | count_statuses_all | MON | 254.056 | 257.090 | 1% |
| mdb-main | S8-S2 | delete_by_id | WRITE | 0.469 | 1.163 | 148% |
| mdb-opt | S8-S2 | delete_by_id | WRITE | 0.402 | 0.415 | 3% |
| mdb-main | S8-S2 | insert | WRITE | 0.655 | 0.819 | 25% |
| mdb-opt | S8-S2 | insert | WRITE | 0.554 | 0.631 | 14% |
| mdb-main | S8-S2 | keys_nonstrict | HOT | 1.634 | 1.709 | 5% |
| mdb-opt | S8-S2 | keys_nonstrict | HOT | 0.791 | 0.775 | 2% |
| mdb-main | S8-S2 | keys_strict | HOT | 0.929 | 0.952 | 2% |
| mdb-opt | S8-S2 | keys_strict | HOT | 0.780 | 0.754 | 3% |
| mdb-main | S8-S2 | oldest_created | MON | 0.952 | 0.949 | 0% |
| mdb-opt | S8-S2 | oldest_created | MON | 0.716 | 0.705 | 2% |
| mdb-main | S8-S2 | oldest_due_nonstrict | MON | 0.586 | 0.581 | 1% |
| mdb-opt | S8-S2 | oldest_due_nonstrict | MON | 0.871 | 0.865 | 1% |
| mdb-main | S8-S2 | oldest_due_strict | MON | 2.163 | 2.215 | 2% |
| mdb-opt | S8-S2 | oldest_due_strict | MON | 0.513 | 0.519 | 1% |
| mdb-main | S8-S2 | pages | HOT | 0.655 | 0.617 | 6% |
| mdb-opt | S8-S2 | pages | HOT | 0.513 | 0.495 | 4% |
| mdb-main | S8-S2 | upd_complete | WRITE | 0.491 | 0.643 | 31% |
| mdb-opt | S8-S2 | upd_complete | WRITE | 0.466 | 0.520 | 12% |
| mdb-main | S8-S2 | upd_fail | WRITE | 0.284 | 0.406 | 43% |
| mdb-opt | S8-S2 | upd_fail | WRITE | 0.380 | 0.437 | 15% |
| mdb-main | S8-S2 | upd_retry | WRITE | 0.259 | 0.421 | 63% |
| mdb-opt | S8-S2 | upd_retry | WRITE | 0.339 | 0.401 | 18% |
| mdb-main | S8-S4 | count_completed | MON | 189.078 | 186.222 | 2% |
| mdb-opt | S8-S4 | count_completed | MON | 206.143 | 122.218 | 69% |
| mdb-main | S8-S4 | count_failed | MON | 0.117 | 0.117 | 0% |
| mdb-opt | S8-S4 | count_failed | MON | 0.123 | 0.113 | 9% |
| mdb-main | S8-S4 | count_new | MON | 175.703 | 174.235 | 1% |
| mdb-opt | S8-S4 | count_new | MON | 189.937 | 112.410 | 69% |
| mdb-main | S8-S4 | count_partition_single | MON | 0.930 | 0.934 | 0% |
| mdb-opt | S8-S4 | count_partition_single | MON | 172.338 | 102.521 | 68% |
| mdb-main | S8-S4 | count_partitions | MON | 61.279 | 61.112 | 0% |
| mdb-opt | S8-S4 | count_partitions | MON | 198.027 | 117.210 | 69% |
| mdb-opt | S8-S4 | count_statuses | MON | 218.070 | 128.803 | 69% |
| mdb-opt | S8-S4 | count_statuses_all | MON | 482.133 | 284.634 | 69% |
| mdb-main | S8-S4 | delete_by_id | WRITE | 0.543 | 0.554 | 2% |
| mdb-opt | S8-S4 | delete_by_id | WRITE | 0.612 | 0.618 | 1% |
| mdb-main | S8-S4 | insert | WRITE | 0.692 | 0.661 | 5% |
| mdb-opt | S8-S4 | insert | WRITE | 0.595 | 0.602 | 1% |
| mdb-main | S8-S4 | keys_nonstrict | HOT | 27557.175 | 23482.614 | 17% |
| mdb-opt | S8-S4 | keys_nonstrict | HOT | 0.922 | 0.849 | 9% |
| mdb-main | S8-S4 | keys_strict | HOT | 1.105 | 1.120 | 1% |
| mdb-opt | S8-S4 | keys_strict | HOT | 0.848 | 0.795 | 7% |
| mdb-main | S8-S4 | oldest_created | MON | 15214.241 | 32501.545 | 114% |
| mdb-opt | S8-S4 | oldest_created | MON | 296.565 | 179.126 | 66% |
| mdb-main | S8-S4 | oldest_due_nonstrict | MON | 90.860 | 90.330 | 1% |
| mdb-opt | S8-S4 | oldest_due_nonstrict | MON | 278.051 | 159.013 | 75% |
| mdb-main | S8-S4 | oldest_due_strict | MON | 21951.585 | 49243.867 | 124% |
| mdb-opt | S8-S4 | oldest_due_strict | MON | 0.604 | 0.550 | 10% |
| mdb-main | S8-S4 | pages | HOT | 0.543 | 0.532 | 2% |
| mdb-opt | S8-S4 | pages | HOT | 0.534 | 0.490 | 9% |
| mdb-main | S8-S4 | upd_complete | WRITE | 0.873 | 0.916 | 5% |
| mdb-opt | S8-S4 | upd_complete | WRITE | 0.949 | 0.916 | 4% |
| mdb-main | S8-S4 | upd_fail | WRITE | 0.237 | 0.240 | 1% |
| mdb-opt | S8-S4 | upd_fail | WRITE | 0.417 | 0.254 | 64% |
| mdb-main | S8-S4 | upd_retry | WRITE | 0.342 | 0.366 | 7% |
| mdb-opt | S8-S4 | upd_retry | WRITE | 0.588 | 0.491 | 20% |
