# Noise (mariadb): `pass1` vs `loadB`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| mdb-main | HOT | 6 | 64% | 84% | S8-S4/keys_nonstrict (27557.17 vs 50751.75 ms) |
| mdb-main | WRITE | 6 | 119% | 140% | S8-S2/insert (0.66 vs 1.57 ms) |
| mdb-main | MON | 14 | 107% | 179% | S8-S4/oldest_due_strict (21951.58 vs 61328.41 ms) |
| mdb-opt | HOT | 6 | 20% | 31% | S8-S2/keys_strict (0.78 vs 0.59 ms) |
| mdb-opt | WRITE | 9 | 223% | 258% | S8-S4/delete_by_id (0.61 vs 2.19 ms) |
| mdb-opt | MON | 17 | 94% | 97% | S8-S2/count_statuses_all (254.06 vs 128.69 ms) |
| all | HOT | 12 | 43% | 84% | S8-S4/keys_nonstrict (27557.17 vs 50751.75 ms) |
| all | WRITE | 15 | 194% | 258% | S8-S4/delete_by_id (0.61 vs 2.19 ms) |
| all | MON | 31 | 100% | 179% | S8-S4/oldest_due_strict (21951.58 vs 61328.41 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| mdb-main | WRITE | 4 | 0.065 | 0.071 |
| mdb-main | MON | 2 | 0.038 | 0.039 |
| mdb-opt | WRITE | 1 | 0.159 | 0.159 |
| mdb-opt | MON | 3 | 0.136 | 0.147 |
| all | WRITE | 5 | 0.124 | 0.159 |
| all | MON | 5 | 0.125 | 0.147 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| mdb-main | S8-S2 | count_completed | MON | 188.680 | 97.297 | 94% |
| mdb-opt | S8-S2 | count_completed | MON | 204.494 | 104.337 | 96% |
| mdb-main | S8-S2 | count_failed | MON | 3.274 | 1.794 | 82% |
| mdb-opt | S8-S2 | count_failed | MON | 3.486 | 1.817 | 92% |
| mdb-main | S8-S2 | count_new | MON | 0.348 | 0.344 | 1% |
| mdb-opt | S8-S2 | count_new | MON | 0.368 | 0.221 | 67% |
| mdb-main | S8-S2 | count_partition_single | MON | 0.133 | 0.094 | 41% |
| mdb-opt | S8-S2 | count_partition_single | MON | 0.353 | 0.262 | 35% |
| mdb-main | S8-S2 | count_partitions | MON | 0.499 | 0.342 | 46% |
| mdb-opt | S8-S2 | count_partitions | MON | 0.474 | 0.340 | 39% |
| mdb-opt | S8-S2 | count_statuses | MON | 4.702 | 2.440 | 93% |
| mdb-opt | S8-S2 | count_statuses_all | MON | 254.056 | 128.694 | 97% |
| mdb-main | S8-S2 | delete_by_id | WRITE | 0.469 | 0.930 | 98% |
| mdb-opt | S8-S2 | delete_by_id | WRITE | 0.402 | 1.057 | 163% |
| mdb-main | S8-S2 | insert | WRITE | 0.655 | 1.575 | 140% |
| mdb-opt | S8-S2 | insert | WRITE | 0.554 | 1.013 | 83% |
| mdb-main | S8-S2 | keys_nonstrict | HOT | 1.634 | 1.130 | 45% |
| mdb-opt | S8-S2 | keys_nonstrict | HOT | 0.791 | 0.728 | 9% |
| mdb-main | S8-S2 | keys_strict | HOT | 0.929 | 0.909 | 2% |
| mdb-opt | S8-S2 | keys_strict | HOT | 0.780 | 0.595 | 31% |
| mdb-main | S8-S2 | oldest_created | MON | 0.952 | 0.622 | 53% |
| mdb-opt | S8-S2 | oldest_created | MON | 0.716 | 0.469 | 53% |
| mdb-main | S8-S2 | oldest_due_nonstrict | MON | 0.586 | 0.546 | 7% |
| mdb-opt | S8-S2 | oldest_due_nonstrict | MON | 0.871 | 0.737 | 18% |
| mdb-main | S8-S2 | oldest_due_strict | MON | 2.163 | 1.451 | 49% |
| mdb-opt | S8-S2 | oldest_due_strict | MON | 0.513 | 0.416 | 23% |
| mdb-main | S8-S2 | pages | HOT | 0.655 | 0.610 | 7% |
| mdb-opt | S8-S2 | pages | HOT | 0.513 | 0.495 | 4% |
| mdb-main | S8-S2 | upd_complete | WRITE | 0.491 | 0.783 | 59% |
| mdb-opt | S8-S2 | upd_complete | WRITE | 0.466 | 0.849 | 82% |
| mdb-main | S8-S2 | upd_fail | WRITE | 0.284 | 0.281 | 1% |
| mdb-opt | S8-S2 | upd_fail | WRITE | 0.380 | 0.486 | 28% |
| mdb-main | S8-S2 | upd_retry | WRITE | 0.259 | 0.253 | 2% |
| mdb-opt | S8-S2 | upd_retry | WRITE | 0.339 | 0.469 | 38% |
| mdb-main | S8-S4 | count_completed | MON | 189.078 | 94.750 | 100% |
| mdb-opt | S8-S4 | count_completed | MON | 206.143 | 208.930 | 1% |
| mdb-main | S8-S4 | count_failed | MON | 0.117 | 0.088 | 33% |
| mdb-opt | S8-S4 | count_failed | MON | 0.123 | 0.122 | 1% |
| mdb-main | S8-S4 | count_new | MON | 175.703 | 87.658 | 100% |
| mdb-opt | S8-S4 | count_new | MON | 189.937 | 193.686 | 2% |
| mdb-main | S8-S4 | count_partition_single | MON | 0.930 | 0.503 | 85% |
| mdb-opt | S8-S4 | count_partition_single | MON | 172.338 | 173.953 | 1% |
| mdb-main | S8-S4 | count_partitions | MON | 61.279 | 30.520 | 101% |
| mdb-opt | S8-S4 | count_partitions | MON | 198.027 | 203.241 | 3% |
| mdb-opt | S8-S4 | count_statuses | MON | 218.070 | 222.847 | 2% |
| mdb-opt | S8-S4 | count_statuses_all | MON | 482.133 | 489.862 | 2% |
| mdb-main | S8-S4 | delete_by_id | WRITE | 0.543 | 0.496 | 9% |
| mdb-opt | S8-S4 | delete_by_id | WRITE | 0.612 | 2.194 | 258% |
| mdb-main | S8-S4 | insert | WRITE | 0.692 | 0.652 | 6% |
| mdb-opt | S8-S4 | insert | WRITE | 0.595 | 1.435 | 141% |
| mdb-main | S8-S4 | keys_nonstrict | HOT | 27557.175 | 50751.745 | 84% |
| mdb-opt | S8-S4 | keys_nonstrict | HOT | 0.922 | 0.894 | 3% |
| mdb-main | S8-S4 | keys_strict | HOT | 1.105 | 0.934 | 18% |
| mdb-opt | S8-S4 | keys_strict | HOT | 0.848 | 0.814 | 4% |
| mdb-main | S8-S4 | oldest_created | MON | 15214.241 | 31936.280 | 110% |
| mdb-opt | S8-S4 | oldest_created | MON | 296.565 | 302.045 | 2% |
| mdb-main | S8-S4 | oldest_due_nonstrict | MON | 90.860 | 46.653 | 95% |
| mdb-opt | S8-S4 | oldest_due_nonstrict | MON | 278.051 | 286.713 | 3% |
| mdb-main | S8-S4 | oldest_due_strict | MON | 21951.585 | 61328.412 | 179% |
| mdb-opt | S8-S4 | oldest_due_strict | MON | 0.604 | 0.598 | 1% |
| mdb-main | S8-S4 | pages | HOT | 0.543 | 0.479 | 13% |
| mdb-opt | S8-S4 | pages | HOT | 0.534 | 0.539 | 1% |
| mdb-main | S8-S4 | upd_complete | WRITE | 0.873 | 0.822 | 6% |
| mdb-opt | S8-S4 | upd_complete | WRITE | 0.949 | 2.979 | 214% |
| mdb-main | S8-S4 | upd_fail | WRITE | 0.237 | 0.166 | 43% |
| mdb-opt | S8-S4 | upd_fail | WRITE | 0.417 | 0.258 | 62% |
| mdb-main | S8-S4 | upd_retry | WRITE | 0.342 | 0.292 | 17% |
| mdb-opt | S8-S4 | upd_retry | WRITE | 0.588 | 0.960 | 63% |
