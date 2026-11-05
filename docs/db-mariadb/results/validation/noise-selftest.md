# Noise (mariadb): `../results/validation` vs `../results/validation`, median ms

Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= 0.3 ms; smaller ones are listed as absolute differences.

## Relative spread per group

| variant | group | n | p90 spread | max spread | worst (label/query) |
|---|---|---|---|---|---|
| mdb-main | RET | 2 | 87% | 95% | S1/find_completed_before (11.68 vs 22.77 ms) |
| all | RET | 2 | 87% | 95% | S1/find_completed_before (11.68 vs 22.77 ms) |

## Queries below 0.3 ms: absolute difference |m1-m2| (ms) per group

| variant | group | n | p90 abs diff ms | max abs diff ms |
|---|---|---|---|---|

## All queries

| variant | label | query | group | m1 ms | m2 ms | spread |
|---|---|---|---|---|---|---|
| mdb-main | S1 | find_completed_before | RET | 11.675 | 22.765 | 95% |
| mdb-main | S1 | ids_completed_before | RET | 9.361 | 10.478 | 12% |
