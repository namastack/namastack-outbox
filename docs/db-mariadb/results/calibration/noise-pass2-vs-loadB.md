# Noise (mariadb): `pass2` vs `loadB`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| mdb-main | HOT | 6 | 76% | 104% | S8-S4/keys_nonstrict (24817.88 vs 50751.75 ms) |
| mdb-main | WRITE | 6 | 118% | 120% | S8-S2/insert (0.72 vs 1.57 ms) |
| mdb-main | MON | 14 | 120% | 174% | S8-S4/oldest_due_strict (22394.48 vs 61328.41 ms) |
| mdb-opt | HOT | 6 | 21% | 30% | S8-S2/keys_strict (0.77 vs 0.59 ms) |
| mdb-opt | WRITE | 7 | 271% | 319% | S8-S4/delete_by_id (0.52 vs 2.19 ms) |
| mdb-opt | MON | 17 | 95% | 100% | S8-S2/count_statuses_all (256.92 vs 128.69 ms) |
| all | HOT | 12 | 45% | 104% | S8-S4/keys_nonstrict (24817.88 vs 50751.75 ms) |
| all | WRITE | 13 | 223% | 319% | S8-S4/delete_by_id (0.52 vs 2.19 ms) |
| all | MON | 31 | 102% | 174% | S8-S4/oldest_due_strict (22394.48 vs 61328.41 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|
| mdb-main | WRITE | 4 | 0.177 | 0.195 |
| mdb-main | MON | 2 | 0.066 | 0.068 |
| mdb-opt | WRITE | 3 | 0.216 | 0.219 |
| mdb-opt | MON | 3 | 0.152 | 0.163 |
| all | WRITE | 7 | 0.210 | 0.219 |
| all | MON | 5 | 0.141 | 0.163 |

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| mdb-main | S8-S2 | count_completed | MON | 186.294 | 97.297 | 91% |
| mdb-opt | S8-S2 | count_completed | MON | 203.172 | 104.337 | 95% |
| mdb-main | S8-S2 | count_failed | MON | 3.263 | 1.794 | 82% |
| mdb-opt | S8-S2 | count_failed | MON | 3.472 | 1.817 | 91% |
| mdb-main | S8-S2 | count_new | MON | 0.345 | 0.344 | 0% |
| mdb-opt | S8-S2 | count_new | MON | 0.384 | 0.221 | 74% |
| mdb-main | S8-S2 | count_partition_single | MON | 0.138 | 0.094 | 47% |
| mdb-opt | S8-S2 | count_partition_single | MON | 0.370 | 0.262 | 41% |
| mdb-main | S8-S2 | count_partitions | MON | 0.496 | 0.342 | 45% |
| mdb-opt | S8-S2 | count_partitions | MON | 0.521 | 0.340 | 53% |
| mdb-opt | S8-S2 | count_statuses | MON | 4.778 | 2.440 | 96% |
| mdb-opt | S8-S2 | count_statuses_all | MON | 256.917 | 128.694 | 100% |
| mdb-main | S8-S2 | delete_by_id | WRITE | 0.430 | 0.930 | 116% |
| mdb-opt | S8-S2 | delete_by_id | WRITE | 0.405 | 1.057 | 161% |
| mdb-main | S8-S2 | insert | WRITE | 0.716 | 1.575 | 120% |
| mdb-opt | S8-S2 | insert | WRITE | 0.515 | 1.013 | 97% |
| mdb-main | S8-S2 | keys_nonstrict | HOT | 1.662 | 1.130 | 47% |
| mdb-opt | S8-S2 | keys_nonstrict | HOT | 0.785 | 0.728 | 8% |
| mdb-main | S8-S2 | keys_strict | HOT | 0.995 | 0.909 | 9% |
| mdb-opt | S8-S2 | keys_strict | HOT | 0.774 | 0.595 | 30% |
| mdb-main | S8-S2 | oldest_created | MON | 0.972 | 0.622 | 56% |
| mdb-opt | S8-S2 | oldest_created | MON | 0.674 | 0.469 | 44% |
| mdb-main | S8-S2 | oldest_due_nonstrict | MON | 0.597 | 0.546 | 9% |
| mdb-opt | S8-S2 | oldest_due_nonstrict | MON | 0.862 | 0.737 | 17% |
| mdb-main | S8-S2 | oldest_due_strict | MON | 2.216 | 1.451 | 53% |
| mdb-opt | S8-S2 | oldest_due_strict | MON | 0.499 | 0.416 | 20% |
| mdb-main | S8-S2 | pages | HOT | 0.624 | 0.610 | 2% |
| mdb-opt | S8-S2 | pages | HOT | 0.490 | 0.495 | 1% |
| mdb-main | S8-S2 | upd_complete | WRITE | 0.575 | 0.783 | 36% |
| mdb-opt | S8-S2 | upd_complete | WRITE | 0.379 | 0.849 | 124% |
| mdb-main | S8-S2 | upd_fail | WRITE | 0.299 | 0.281 | 6% |
| mdb-opt | S8-S2 | upd_fail | WRITE | 0.282 | 0.486 | 72% |
| mdb-main | S8-S2 | upd_retry | WRITE | 0.358 | 0.253 | 42% |
| mdb-opt | S8-S2 | upd_retry | WRITE | 0.250 | 0.469 | 88% |
| mdb-main | S8-S4 | count_completed | MON | 189.088 | 94.750 | 100% |
| mdb-opt | S8-S4 | count_completed | MON | 205.305 | 208.930 | 2% |
| mdb-main | S8-S4 | count_failed | MON | 0.156 | 0.088 | 77% |
| mdb-opt | S8-S4 | count_failed | MON | 0.129 | 0.122 | 6% |
| mdb-main | S8-S4 | count_new | MON | 176.633 | 87.658 | 102% |
| mdb-opt | S8-S4 | count_new | MON | 190.772 | 193.686 | 2% |
| mdb-main | S8-S4 | count_partition_single | MON | 0.927 | 0.503 | 84% |
| mdb-opt | S8-S4 | count_partition_single | MON | 175.534 | 173.953 | 1% |
| mdb-main | S8-S4 | count_partitions | MON | 62.060 | 30.520 | 103% |
| mdb-opt | S8-S4 | count_partitions | MON | 196.591 | 203.241 | 3% |
| mdb-opt | S8-S4 | count_statuses | MON | 221.626 | 222.847 | 1% |
| mdb-opt | S8-S4 | count_statuses_all | MON | 485.107 | 489.862 | 1% |
| mdb-main | S8-S4 | delete_by_id | WRITE | 0.554 | 0.496 | 12% |
| mdb-opt | S8-S4 | delete_by_id | WRITE | 0.524 | 2.194 | 319% |
| mdb-main | S8-S4 | insert | WRITE | 0.920 | 0.652 | 41% |
| mdb-opt | S8-S4 | insert | WRITE | 0.612 | 1.435 | 134% |
| mdb-main | S8-S4 | keys_nonstrict | HOT | 24817.877 | 50751.745 | 104% |
| mdb-opt | S8-S4 | keys_nonstrict | HOT | 1.006 | 0.894 | 13% |
| mdb-main | S8-S4 | keys_strict | HOT | 1.187 | 0.934 | 27% |
| mdb-opt | S8-S4 | keys_strict | HOT | 0.907 | 0.814 | 11% |
| mdb-main | S8-S4 | oldest_created | MON | 14051.846 | 31936.280 | 127% |
| mdb-opt | S8-S4 | oldest_created | MON | 297.722 | 302.045 | 1% |
| mdb-main | S8-S4 | oldest_due_nonstrict | MON | 90.553 | 46.653 | 94% |
| mdb-opt | S8-S4 | oldest_due_nonstrict | MON | 281.456 | 286.713 | 2% |
| mdb-main | S8-S4 | oldest_due_strict | MON | 22394.481 | 61328.412 | 174% |
| mdb-opt | S8-S4 | oldest_due_strict | MON | 0.588 | 0.598 | 2% |
| mdb-main | S8-S4 | pages | HOT | 0.534 | 0.479 | 11% |
| mdb-opt | S8-S4 | pages | HOT | 0.536 | 0.539 | 1% |
| mdb-main | S8-S4 | upd_complete | WRITE | 0.957 | 0.822 | 16% |
| mdb-opt | S8-S4 | upd_complete | WRITE | 0.880 | 2.979 | 239% |
| mdb-main | S8-S4 | upd_fail | WRITE | 0.301 | 0.166 | 81% |
| mdb-opt | S8-S4 | upd_fail | WRITE | 0.249 | 0.258 | 4% |
| mdb-main | S8-S4 | upd_retry | WRITE | 0.487 | 0.292 | 67% |
| mdb-opt | S8-S4 | upd_retry | WRITE | 0.367 | 0.960 | 162% |
