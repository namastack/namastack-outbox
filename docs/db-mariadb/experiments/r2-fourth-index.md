# Round 2 - the 4th index and UUIDv7 (screen)

MariaDB 12.3.3, lane 1 only (lane 2 idle during every timed step), one pass per cell, stage `screen`. Base `mdb-opt` `2a2663de2f2028790180399293981f7eca587ad1` (worktree clean, never modified). Spec `experiments/round2-spec.md` (part B reduced to `keys_nonstrict` by user decision). Raw data `results/r2/<run>/mariadb/<label>/` (timings.csv, handlers.csv, plans/, env.txt, writecost.csv, concurrency-*.txt), id version per run in `results/r2/<run>/ids.txt` (HARNESS_ID_VERSION unset for v4, 7 for v7; snapshot marker `ids=uuidv7` checked before every step, id nibble of the run schema logged in `results/r2/logs/r2.log`). Equivalence `results/r2/equiv-v4/`, `results/r2/equiv-v7/`. `results/r2/failures.txt` does not exist: no failed step, no step stopped by a per-query limit. Host load at every step start 0.8-2.6 (rule: rerun only if > 18; nothing rerun). Drivers `experiments/r2/run-r2.sh`, `run-equiv.sh`; tables by `summarize.py`. No harness code was changed.

Base (a) in every run: DDL `experiments/r2/{base,sp,spc}/ddl.sql` (i1, i2, i3 [+ 4th index]), queries `mdb-opt` + overlays `experiments/r2/forms-a/queries` (ks-05-head, kn-05-head, oc-03-order-id, odn-01-order). Runs `r2-{base,sp,spc,base2}-{v4,v7}`; `base2` = second reference pass (drift check). `sp` = `(status, partition_no)`, `spc` = `(status, partition_no, created_at)`.

Noise thresholds (STATE.md): HOT 11 %, MON 10 % (0.04 ms absolute below 0.3 ms), writecost redo 12 %, latency p50 8 % / p95 25 %, pages/write 64 % (supporting evidence only).

## 1. Verdict

* **Winner: `spc` `(status, partition_no, created_at)` for both id versions.**
  * v4: clear. S4 inserts stay at the reference (1-thread redo 2607 vs 2359/2379 B, +10 %; `sp` 5764 B, +144 %); S2 completions and deletes cost less than `sp` (still 1.7-3.1x the reference).
  * v7: `sp` and `spc` tie on write cost within noise (v7 removes the insert penalty for both); `spc` keeps S10 at the reference, `sp` loses 8 % (v7) / 14-17 % (v4).
* **count_partitions at partial ownership** (qualifies, within 2x of main on every label): S16-S4 7.4 ms (main 7.5, reference 106), S8-S4 29.5 ms (main 61, reference 115), S8-S5 4.3 ms (main 8.7, reference 25). Identical for v4 and v7. At full ownership (S4, S5, 256 partitions) the planner uses an index scan over all NEW rows; `spc` gains nothing (S4 119 vs 127 ms, S5 27 vs 27 ms), `sp` gains 14 % / 23 %.
* **Price of `spc` against the reference** (redo B/write, S2-wc unless noted, v4 | v7): completion 8651 vs 5119/4430 (+70-95 %) | 11826 vs 7414 (+60 %); delete 5762 vs 1865/1774 (3.1x) | 5548 vs 1513 (3.7x); retry/fail +2 % / +30-40 % | +0 % / +10-15 %; S4-wc inserts +10 % (v4) / +12 % (v7); S4-wc completion/retry/fail/delete no difference in v4, delete +35 % in v7. Insert and update latency p50/p95: no difference. 4-thread insert throughput: no difference (v4 S4 3030 vs 2619/3078 rec/s). Index bytes: +66 MB at load (+23 %), +3-8 MB growth during S2-wc/S4-wc writes (reference total 281 MB, `spc` 347 MB on S2; 520 vs 642 MB on S4).
* **P1 reads:** query plans are unchanged (section 6), but the 4th index adds a constant to the HOT queries: `keys_strict` / `keys_nonstrict` +0.05-0.13 ms (+14-30 % relative on S8-S4, S16-S4, S4, S8-S5), `oldest_*` +0.05-0.25 ms (up to 2.4x on `oldest_due_nonstrict` S8-S4: 0.17 -> 0.38 ms). Beyond the 11 % / 10 % thresholds in relative terms, absolute <= 0.25 ms; `pages` unchanged; `count_new`, `count_statuses` improve 7-17 %. Cause not isolated (hypothesis: extra range estimation over a 4th index at optimization time). This is the only decision-rule item that is not clean for either index; `sp` and `spc` are equal here. Flagged for the confirm stage (see 8).
* **v7 effect** (also on the reference): PK 1362 -> 976 MB (S2), 2656 -> 2286 MB (S4); insert redo 2194 -> 1520 B/write (S2), pages written per insert 1.25 -> 0.17; S2 completion/retry/fail redo +45-140 % (7414/6644/7460 vs 5119/3232/3123 B; cause not isolated), S4 retry redo 14281 -> 4424 B; S10 423/436 -> 911/920 rec/s (2.1x), `pages` p50 5.4 -> 0.21 ms; HOT/MON reads unchanged. v7 removes the `sp` insert penalty (S4 1-thread 5764 -> 1681 B) and keeps ranking spc >= sp.
* **S10:** see section 4. **Part B:** `kn-05-head` stays below `kn-00-base` at K = 10, 100, 1000 (section 7). **Part C:** `forms-e5h` equivalence PASS on S1, S5, S7 (section 8). **Plans:** no false-estimate plan won by the 4th index (section 6).

## 2. UUIDv4 (v4)
### Write cost (S2-wc, S4-wc)

Per phase `redo bytes / pages written / p50 ms / p95 ms / throughput per s`, per write; sizes in MB before -> after.

```
### S2-wc v4: redo B/write | pages/write | p50 ms | p95 ms | thr/s
base   ins1t: 2194 / 1.25 / 0.74 / 0.97 / 1285 | ins4t: 2238 / 1.24 / 1.33 / 2.11 / 2746 | updco: 5119 / 1.76 / 0.72 / 0.99 / 143 | updre: 3232 / 2.41 / 0.67 / 0.89 / 149 | updfa: 3123 / 2.38 / 0.70 / 0.89 / 148 | delby: 1865 / 3.60 / 0.59 / 0.68 / 151
       sizes MB before->after: PRIMARY:1362->1374, key_completed_created_id:95->97, status_completed_created_id:114->115, status_completed_retry:71->72 total idx 281->285 MB, all redo 27 MB
sp     ins1t: 2246 / 1.23 / 0.66 / 0.81 / 1438 | ins4t: 2304 / 1.25 / 1.16 / 1.83 / 3158 | updco: 10323 / 4.41 / 0.72 / 0.97 / 144 | updre: 3011 / 2.38 / 0.66 / 0.85 / 149 | updfa: 4028 / 2.95 / 0.64 / 0.83 / 150 | delby: 7506 / 6.12 / 0.57 / 0.64 / 152
       sizes MB before->after: PRIMARY:1362->1373, key_completed_created_id:95->97, status_completed_created_id:114->115, status_completed_retry:71->72, status_partition:62->66 total idx 342->350 MB, all redo 31 MB
spc    ins1t: 2306 / 1.24 / 0.66 / 0.83 / 1436 | ins4t: 2324 / 1.29 / 1.14 / 1.82 / 3166 | updco: 8651 / 3.71 / 0.72 / 1.02 / 144 | updre: 3057 / 2.42 / 0.63 / 0.72 / 150 | updfa: 4118 / 3.03 / 0.66 / 0.85 / 149 | delby: 5762 / 4.77 / 0.56 / 0.68 / 152
       sizes MB before->after: PRIMARY:1362->1374, key_completed_created_id:95->97, status_completed_created_id:114->115, status_completed_retry:71->72, status_partition_created:66->69 total idx 347->354 MB, all redo 30 MB
base2  ins1t: 2273 / 1.25 / 0.65 / 0.81 / 1452 | ins4t: 2214 / 1.25 / 1.16 / 1.90 / 3121 | updco: 4430 / 2.76 / 0.65 / 0.88 / 146 | updre: 2940 / 2.15 / 0.69 / 0.87 / 144 | updfa: 2892 / 2.38 / 0.68 / 0.82 / 144 | delby: 1774 / 3.58 / 0.58 / 0.70 / 146
       sizes MB before->after: PRIMARY:1362->1374, key_completed_created_id:95->97, status_completed_created_id:114->115, status_completed_retry:71->72 total idx 281->285 MB, all redo 26 MB
### S4-wc v4: redo B/write | pages/write | p50 ms | p95 ms | thr/s
base   ins1t: 2359 / 1.28 / 0.67 / 0.87 / 1404 | ins4t: 2353 / 1.28 / 1.17 / 1.97 / 2619 | updco: 13984 / 6.25 / 0.88 / 1.33 / 562 | updre: 14281 / 3.42 / 0.78 / 1.05 / 619 | updfa: 3971 / 1.62 / 0.63 / 0.81 / 688 | delby: 1719 / 0.93 / 0.61 / 0.75 / 980
       sizes MB before->after: PRIMARY:2656->2669, key_completed_created_id:181->199, status_completed_created_id:212->226, status_completed_retry:127->130 total idx 520->555 MB, all redo 193 MB
sp     ins1t: 5764 / 2.49 / 0.73 / 0.98 / 1276 | ins4t: 3077 / 2.16 / 1.32 / 2.48 / 2554 | updco: 14896 / 6.65 / 0.93 / 1.53 / 490 | updre: 14319 / 3.63 / 0.79 / 1.05 / 616 | updfa: 4164 / 1.69 / 0.64 / 0.86 / 583 | delby: 2116 / 0.97 / 0.67 / 0.85 / 569
       sizes MB before->after: PRIMARY:2656->2669, key_completed_created_id:181->199, status_completed_created_id:212->229, status_completed_retry:127->130, status_partition:113->165 total idx 633->723 MB, all redo 222 MB
spc    ins1t: 2607 / 1.48 / 0.69 / 0.89 / 1371 | ins4t: 2394 / 1.33 / 1.18 / 1.91 / 3030 | updco: 14570 / 7.75 / 0.98 / 1.96 / 473 | updre: 14253 / 3.81 / 0.79 / 1.09 / 612 | updfa: 4124 / 3.32 / 0.72 / 0.99 / 565 | delby: 2106 / 1.02 / 0.61 / 0.76 / 817
       sizes MB before->after: PRIMARY:2656->2669, key_completed_created_id:181->196, status_completed_created_id:212->231, status_completed_retry:127->130, status_partition_created:122->130 total idx 642->686 MB, all redo 200 MB
base2  ins1t: 2379 / 1.30 / 0.68 / 0.87 / 1391 | ins4t: 2306 / 1.27 / 1.16 / 1.91 / 3078 | updco: 13851 / 5.84 / 0.88 / 1.30 / 564 | updre: 14276 / 3.80 / 0.78 / 1.06 / 702 | updfa: 3977 / 1.70 / 0.63 / 0.83 / 794 | delby: 1721 / 1.05 / 0.61 / 0.76 / 815
       sizes MB before->after: PRIMARY:2656->2668, key_completed_created_id:181->199, status_completed_created_id:212->229, status_completed_retry:127->129 total idx 520->556 MB, all redo 193 MB
```
### Reads: partial ownership first (S8-S4, S16-S4, S8-S5), then full ownership (S4, S5), median ms; base/base2 = reference first/last

```
### S8-S4 v4: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.39           0.50           0.49           0.38          
keys_nonstrict           0.42           0.55           0.55           0.42          
pages                    0.33           0.33           0.32           0.31          
insert                   0.36           0.51           0.47           0.50          
upd_complete             0.54           0.89           0.91           0.70          
upd_retry                0.28           0.40           0.43           0.40          
upd_fail                 0.16           0.17           0.27           0.16          
delete_by_id             0.43           0.40           0.50           0.39          
count_new                102.67         85.02          92.92          102.36        
count_failed             0.08           0.08           0.10           0.09          
count_completed          107.99         92.38          100.02         108.30        
count_statuses           118.45         99.21          107.12         115.94        
count_statuses_all       333.12         218.22         234.02         326.76        
count_partitions         115.56         27.24          29.49          114.86        
count_partition_single   95.92          0.46           0.49           95.86         
oldest_created           0.16           0.26           0.26           0.16          
oldest_due_strict        0.24           0.36           0.35           0.26          
oldest_due_nonstrict     0.17           0.41           0.38           0.19          
### S16-S4 v4: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.38           0.43           0.43           0.37          
keys_nonstrict           0.38           0.46           0.46           0.39          
pages                    0.33           0.33           0.34           0.33          
insert                   0.39           0.40           0.40           0.42          
upd_complete             0.70           0.87           0.81           0.65          
upd_retry                0.39           0.38           0.36           0.39          
upd_fail                 0.26           0.26           0.25           0.27          
delete_by_id             0.52           0.53           0.52           0.46          
count_new                102.22         84.85          92.77          102.22        
count_failed             0.08           0.10           0.08           0.09          
count_completed          108.16         92.28          100.57         107.88        
count_statuses           118.44         98.95          107.14         116.82        
count_statuses_all       338.85         217.91         234.00         330.04        
count_partitions         105.65         6.87           7.39           105.67        
count_partition_single   95.82          0.44           0.48           96.10         
oldest_created           0.13           0.18           0.19           0.14          
oldest_due_strict        0.22           0.29           0.28           0.23          
oldest_due_nonstrict     0.15           0.20           0.21           0.16          
### S4 v4: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.57           0.66           0.66           0.57          
keys_nonstrict           0.56           0.68           0.67           0.59          
pages                    0.32           0.32           0.33           0.33          
insert                   0.41           0.45           0.42           0.39          
upd_complete             0.63           0.89           0.86           0.63          
upd_retry                0.37           0.36           0.37           0.37          
upd_fail                 0.25           0.26           0.26           0.24          
delete_by_id             0.51           0.54           0.56           0.49          
count_new                101.97         84.84          92.75          102.42        
count_failed             0.09           0.08           0.10           0.09          
count_completed          107.92         92.64          99.63          108.15        
count_statuses           118.33         98.94          107.07         116.87        
count_statuses_all       320.28         218.12         234.23         334.33        
count_partitions         127.93         110.51         118.56         127.28        
count_partition_single   95.99          0.46           0.49           95.25         
oldest_created           0.25           0.33           0.35           0.23          
oldest_due_strict        0.36           0.42           0.42           0.36          
oldest_due_nonstrict     0.35           0.36           0.39           0.35          
### S8-S5 v4: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              53.57          53.83          53.61          52.94         
keys_nonstrict           0.50           0.58           0.59           0.52          
pages                    1270.32        1261.73        1277.88        1274.13       
insert                   0.38           0.47           0.46           0.36          
upd_complete             0.40           0.39           0.42           0.38          
upd_retry                0.24           0.24           0.25           0.25          
upd_fail                 0.26           0.25           0.26           0.24          
delete_by_id             0.24           0.24           0.29           0.33          
count_new                20.86          17.18          19.44          21.41         
count_failed             0.08           0.08           0.08           0.10          
count_completed          32.99          28.18          31.78          32.96         
count_statuses           23.18          19.92          21.57          23.52         
count_statuses_all       63.40          54.97          58.73          63.64         
count_partitions         24.74          4.03           4.31           24.59         
count_partition_single   19.98          0.28           0.28           19.79         
oldest_created           0.15           0.21           0.21           0.14          
oldest_due_strict        0.50           0.57           0.57           0.50          
oldest_due_nonstrict     0.18           0.33           0.32           0.18          
### S5 v4: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.59           0.62           0.63           0.59          
keys_nonstrict           0.65           0.69           0.69           0.65          
pages                    1564.20        1557.99        1560.74        1580.12       
insert                   0.37           0.38           0.44           0.37          
upd_complete             0.47           0.67           0.68           0.45          
upd_retry                0.36           0.35           0.34           0.35          
upd_fail                 0.25           0.25           0.26           0.25          
delete_by_id             0.26           0.34           0.33           0.26          
count_new                20.73          17.39          18.66          20.42         
count_failed             0.09           0.09           0.08           0.08          
count_completed          33.08          28.21          30.02          32.85         
count_statuses           23.42          19.79          21.40          23.21         
count_statuses_all       63.40          54.87          58.96          63.15         
count_partitions         27.20          20.90          27.27          27.01         
count_partition_single   19.73          0.27           0.28           20.00         
oldest_created           0.24           0.28           0.28           0.25          
oldest_due_strict        0.63           0.66           0.66           0.62          
oldest_due_nonstrict     0.33           0.41           0.41           0.33          
```
### S10 (S10-S2-p1-unthr)

```
### S10 v4
base processed/s 423.2 inserted/s 2353.7 keys p50 0.54 pages p50 5.44 upd p50 1.17 ins p50 1.20 redo MB 446.202415
sp processed/s 363.6 inserted/s 2226.9 keys p50 0.59 pages p50 5.61 upd p50 1.22 ins p50 1.21 redo MB 452.307938
spc processed/s 421.9 inserted/s 2374.2 keys p50 0.59 pages p50 5.53 upd p50 1.17 ins p50 1.20 redo MB 472.967204
base2 processed/s 436.4 inserted/s 2431.7 keys p50 0.54 pages p50 5.46 upd p50 1.17 ins p50 1.20 redo MB 460.685942
```

## 3. UUIDv7 (v7)
### Write cost (S2-wc, S4-wc)

Per phase `redo bytes / pages written / p50 ms / p95 ms / throughput per s`, per write; sizes in MB before -> after.

```
### S2-wc v7: redo B/write | pages/write | p50 ms | p95 ms | thr/s
base   ins1t: 1520 / 0.17 / 0.56 / 0.62 / 1745 | ins4t: 2519 / 0.28 / 0.98 / 1.10 / 3988 | updco: 7414 / 1.71 / 0.57 / 0.69 / 150 | updre: 6644 / 1.35 / 0.56 / 0.65 / 152 | updfa: 7460 / 1.34 / 0.57 / 0.67 / 151 | delby: 1513 / 1.64 / 0.53 / 0.61 / 152
       sizes MB before->after: PRIMARY:976->991, key_completed_created_id:95->95, status_completed_created_id:114->115, status_completed_retry:71->72 total idx 281->283 MB, all redo 28 MB
sp     ins1t: 1583 / 0.18 / 0.56 / 0.63 / 1740 | ins4t: 2607 / 0.28 / 0.98 / 1.09 / 4008 | updco: 12102 / 3.90 / 0.60 / 0.74 / 150 | updre: 6644 / 1.35 / 0.55 / 0.62 / 152 | updfa: 8278 / 1.92 / 0.57 / 0.69 / 151 | delby: 4928 / 3.47 / 0.54 / 0.60 / 152
       sizes MB before->after: PRIMARY:976->991, key_completed_created_id:95->95, status_completed_created_id:114->115, status_completed_retry:71->72, status_partition:62->64 total idx 342->346 MB, all redo 32 MB
spc    ins1t: 1570 / 0.18 / 0.55 / 0.63 / 1749 | ins4t: 2623 / 0.29 / 0.98 / 1.10 / 3943 | updco: 11826 / 3.96 / 0.60 / 0.72 / 150 | updre: 6644 / 1.35 / 0.58 / 0.66 / 151 | updfa: 8310 / 1.98 / 0.56 / 0.65 / 152 | delby: 5548 / 2.60 / 0.54 / 0.66 / 152
       sizes MB before->after: PRIMARY:976->991, key_completed_created_id:95->95, status_completed_created_id:114->115, status_completed_retry:71->72, status_partition_created:66->70 total idx 347->353 MB, all redo 32 MB
base2  ins1t: 1530 / 0.17 / 0.55 / 0.62 / 1764 | ins4t: 2557 / 0.26 / 0.98 / 1.10 / 3987 | updco: 7414 / 1.71 / 0.57 / 0.67 / 151 | updre: 6644 / 1.35 / 0.58 / 0.66 / 151 | updfa: 7224 / 1.35 / 0.56 / 0.66 / 152 | delby: 1513 / 1.64 / 0.53 / 0.58 / 153
       sizes MB before->after: PRIMARY:976->991, key_completed_created_id:95->95, status_completed_created_id:114->115, status_completed_retry:71->72 total idx 281->283 MB, all redo 28 MB
### S4-wc v7: redo B/write | pages/write | p50 ms | p95 ms | thr/s
base   ins1t: 1519 / 0.17 / 0.56 / 0.63 / 1745 | ins4t: 2511 / 0.26 / 0.98 / 1.10 / 3971 | updco: 14025 / 2.30 / 0.72 / 0.92 / 317 | updre: 4424 / 0.16 / 0.55 / 0.64 / 733 | updfa: 3978 / 0.16 / 0.55 / 0.63 / 735 | delby: 1096 / 0.83 / 0.53 / 0.62 / 873
       sizes MB before->after: PRIMARY:2286->2295, key_completed_created_id:181->201, status_completed_created_id:212->213, status_completed_retry:127->129 total idx 520->543 MB, all redo 138 MB
sp     ins1t: 1681 / 0.27 / 0.56 / 0.67 / 1716 | ins4t: 2565 / 0.31 / 0.98 / 1.09 / 3996 | updco: 14253 / 2.54 / 0.73 / 0.98 / 315 | updre: 4428 / 0.16 / 0.55 / 0.64 / 856 | updfa: 4188 / 0.26 / 0.55 / 0.65 / 730 | delby: 1433 / 0.80 / 0.53 / 0.63 / 867
       sizes MB before->after: PRIMARY:2286->2295, key_completed_created_id:181->200, status_completed_created_id:212->213, status_completed_retry:127->129, status_partition:113->120 total idx 633->662 MB, all redo 143 MB
spc    ins1t: 1696 / 0.27 / 0.56 / 0.67 / 1719 | ins4t: 2545 / 0.31 / 0.98 / 1.10 / 3960 | updco: 14505 / 2.71 / 0.74 / 1.01 / 560 | updre: 4444 / 0.18 / 0.55 / 0.64 / 1036 | updfa: 4222 / 0.31 / 0.55 / 0.65 / 1032 | delby: 1475 / 0.82 / 0.54 / 0.64 / 736
       sizes MB before->after: PRIMARY:2286->2295, key_completed_created_id:181->200, status_completed_created_id:212->213, status_completed_retry:127->129, status_partition_created:122->129 total idx 642->671 MB, all redo 144 MB
base2  ins1t: 1507 / 0.17 / 0.56 / 0.63 / 1744 | ins4t: 2525 / 0.27 / 0.98 / 1.10 / 3939 | updco: 13839 / 2.24 / 0.72 / 0.90 / 571 | updre: 4429 / 0.18 / 0.55 / 0.65 / 1031 | updfa: 3977 / 0.18 / 0.55 / 0.64 / 1033 | delby: 1105 / 0.76 / 0.53 / 0.62 / 1055
       sizes MB before->after: PRIMARY:2286->2294, key_completed_created_id:181->199, status_completed_created_id:212->213, status_completed_retry:127->129 total idx 520->541 MB, all redo 137 MB
```
### Reads: partial ownership first (S8-S4, S16-S4, S8-S5), then full ownership (S4, S5), median ms; base/base2 = reference first/last

```
### S8-S4 v7: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.41           0.49           0.52           0.39          
keys_nonstrict           0.43           0.55           0.55           0.43          
pages                    0.33           0.34           0.33           0.32          
insert                   0.22           0.34           0.35           0.33          
upd_complete             0.53           0.83           0.79           0.50          
upd_retry                0.28           0.26           0.27           0.27          
upd_fail                 0.28           0.26           0.27           0.26          
delete_by_id             0.32           0.32           0.32           0.33          
count_new                102.54         87.36          92.99          106.17        
count_failed             0.09           0.08           0.09           0.09          
count_completed          108.40         95.26          99.76          108.94        
count_statuses           116.73         104.03         106.82         116.98        
count_statuses_all       345.75         226.41         232.69         326.95        
count_partitions         112.65         28.58          29.33          113.44        
count_partition_single   96.69          0.47           0.49           95.96         
oldest_created           0.14           0.27           0.28           0.15          
oldest_due_strict        0.25           0.37           0.37           0.25          
oldest_due_nonstrict     0.17           0.41           0.38           0.18          
### S16-S4 v7: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.37           0.44           0.43           0.37          
keys_nonstrict           0.40           0.47           0.47           0.40          
pages                    0.32           0.33           0.32           0.33          
insert                   0.32           0.35           0.35           0.33          
upd_complete             0.52           0.62           0.63           0.52          
upd_retry                0.28           0.26           0.28           0.27          
upd_fail                 0.26           0.25           0.26           0.26          
delete_by_id             0.34           0.34           0.34           0.33          
count_new                102.49         87.45          96.72          102.57        
count_failed             0.09           0.10           0.09           0.09          
count_completed          108.02         95.28          100.22         108.52        
count_statuses           116.91         100.98         106.64         116.96        
count_statuses_all       326.92         221.00         234.11         327.31        
count_partitions         105.12         6.93           7.33           106.21        
count_partition_single   94.89          0.47           0.50           95.33         
oldest_created           0.13           0.20           0.19           0.12          
oldest_due_strict        0.23           0.28           0.30           0.22          
oldest_due_nonstrict     0.16           0.21           0.22           0.15          
### S4 v7: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.58           0.66           0.67           0.58          
keys_nonstrict           0.60           0.70           0.68           0.58          
pages                    0.33           0.36           0.34           0.33          
insert                   0.31           0.35           0.34           0.31          
upd_complete             0.52           0.85           0.83           0.56          
upd_retry                0.27           0.28           0.28           0.28          
upd_fail                 0.27           0.27           0.27           0.27          
delete_by_id             0.33           0.40           0.39           0.31          
count_new                102.45         91.07          92.91          103.28        
count_failed             0.10           0.09           0.10           0.08          
count_completed          107.90         95.41          99.86          109.44        
count_statuses           116.86         101.30         106.82         117.67        
count_statuses_all       342.38         222.60         232.60         317.56        
count_partitions         128.20         112.59         118.89         127.49        
count_partition_single   96.16          0.46           0.48           95.85         
oldest_created           0.25           0.34           0.34           0.24          
oldest_due_strict        0.37           0.42           0.42           0.35          
oldest_due_nonstrict     0.35           0.36           0.38           0.34          
### S8-S5 v7: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              53.17          54.26          53.51          54.84         
keys_nonstrict           0.51           0.59           0.58           0.49          
pages                    1035.55        1043.23        1037.48        1039.59       
insert                   0.23           0.43           0.40           0.32          
upd_complete             0.32           0.35           0.34           0.33          
upd_retry                0.27           0.26           0.25           0.26          
upd_fail                 0.27           0.27           0.26           0.25          
delete_by_id             0.19           0.19           0.18           0.18          
count_new                21.46          18.52          18.92          20.85         
count_failed             0.09           0.08           0.08           0.09          
count_completed          33.27          29.20          30.46          33.21         
count_statuses           23.35          20.35          21.44          23.40         
count_statuses_all       63.28          57.02          58.75          63.44         
count_partitions         24.18          4.07           4.24           24.45         
count_partition_single   19.80          0.28           0.30           19.63         
oldest_created           0.15           0.22           0.22           0.14          
oldest_due_strict        0.50           0.59           0.59           0.50          
oldest_due_nonstrict     0.18           0.33           0.33           0.18          
### S5 v7: median ms (rows; note)
query                    base           sp             spc            base2         
keys_strict              0.59           0.64           0.63           0.59          
keys_nonstrict           0.63           0.69           0.69           0.64          
pages                    1151.21        1161.55        1162.49        1185.03       
insert                   0.29           0.31           0.30           0.31          
upd_complete             0.56           0.81           0.85           0.52          
upd_retry                0.24           0.24           0.25           0.24          
upd_fail                 0.25           0.25           0.26           0.24          
delete_by_id             0.26           0.29           0.26           0.24          
count_new                21.78          19.94          19.72          20.62         
count_failed             0.09           0.08           0.09           0.08          
count_completed          34.00          30.79          30.51          32.95         
count_statuses           23.15          20.88          21.57          23.36         
count_statuses_all       63.17          57.43          59.23          63.28         
count_partitions         28.26          28.00          27.44          27.20         
count_partition_single   20.62          0.32           0.30           19.80         
oldest_created           0.25           0.29           0.29           0.25          
oldest_due_strict        0.64           0.67           0.69           0.64          
oldest_due_nonstrict     0.34           0.41           0.42           0.34          
```
### S10 (S10-S2-p1-unthr)

```
### S10 v7
base processed/s 910.8 inserted/s 3901.2 keys p50 0.53 pages p50 0.21 upd p50 0.98 ins p50 0.98 redo MB 793.013614
sp processed/s 837.9 inserted/s 3606.3 keys p50 0.57 pages p50 0.21 upd p50 0.98 ins p50 0.99 redo MB 755.966622
spc processed/s 909.3 inserted/s 3921.9 keys p50 0.57 pages p50 0.20 upd p50 0.98 ins p50 0.98 redo MB 816.856707
base2 processed/s 919.7 inserted/s 3942.8 keys p50 0.53 pages p50 0.21 upd p50 0.98 ins p50 0.98 redo MB 799.765189
```

## 6. Plans (S5 data, 16/199/200/256 owned partitions, `ANALYZE FORMAT=JSON`, SQL_SELECT_LIMIT as in the driver)

Per query: access:index(rows_est/r_rows) for each table of the plan; `X` = executed. `results/r2/r2-{sp,spc}-{v4,v7}/mariadb/S5-p{16,199,200,256}/plans/`.

```
## sp-v4
count_partitions:
    p16 X range:status_partition(6012/5999)
    p199 X range:status_partition(190252/153987)
    p200 X ref:status_partition(402955/199980)
    p256 X ref:status_partition(402955/199980)
count_statuses:
    p16 X range:status_partition(384014/200000)
    p199 X range:status_partition(384014/200000)
    p200 X range:status_partition(384014/200000)
    p256 X range:status_partition(384014/200000)
keys_strict:
    p16 X range:status_completed_created_id(402955/199980);ref:key_completed_created_id(1/1)
    p199 X ref:status_completed_created_id(402955/19);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(402955/19);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(402955/16);ref:key_completed_created_id(1/1)
keys_nonstrict:
    p16 X range:status_completed_created_id(402955/199980);ref:key_completed_created_id(1/1.333)
    p199 X ref:status_completed_created_id(402955/19);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(402955/19);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(402955/12);ref:key_completed_created_id(1/1.200)
oldest_created:
    p16 X range:status_completed_created_id(402955/25)
    p199 X ref:status_completed_created_id(402955/1)
    p200 X ref:status_completed_created_id(402955/1)
    p256 X ref:status_completed_created_id(402955/1)
oldest_due_strict:
    p16 X range:status_completed_retry(402955/22);ref:key_completed_created_id(1/2000)
    p199 X range:status_completed_retry(402955/1);ref:key_completed_created_id(1/2000)
    p200 X range:status_completed_retry(402955/1);ref:key_completed_created_id(1/2000)
    p256 X range:status_completed_retry(402955/1);ref:key_completed_created_id(1/2000)
oldest_due_nonstrict:
    p16 X range:status_completed_retry(402955/22)
    p199 X range:status_completed_retry(402955/1)
    p200 X range:status_completed_retry(402955/1)
    p256 X range:status_completed_retry(402955/1)
## spc-v4
count_partitions:
    p16 X range:status_partition_created(6012/5999)
    p199 X range:status_partition_created(190218/153987)
    p200 X ref:status_completed_retry(410758/199980)
    p256 X ref:status_completed_retry(410758/199980)
count_statuses:
    p16 X range:status_partition_created(379088/200000)
    p199 X range:status_partition_created(379088/200000)
    p200 X range:status_partition_created(379088/200000)
    p256 X range:status_partition_created(379088/200000)
keys_strict:
    p16 X range:status_completed_created_id(408356/199980);ref:key_completed_created_id(1/1)
    p199 X ref:status_completed_created_id(408356/19);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(408356/19);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(408356/16);ref:key_completed_created_id(1/1)
keys_nonstrict:
    p16 X range:status_completed_created_id(408356/199980);ref:key_completed_created_id(1/1.333)
    p199 X ref:status_completed_created_id(408356/19);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(408356/19);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(408356/12);ref:key_completed_created_id(1/1.200)
oldest_created:
    p16 X range:status_completed_created_id(408356/25)
    p199 X ref:status_completed_created_id(408356/1)
    p200 X ref:status_completed_created_id(408356/1)
    p256 X ref:status_completed_created_id(408356/1)
oldest_due_strict:
    p16 X range:status_completed_retry(410718/22);ref:key_completed_created_id(1/2000)
    p199 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
    p200 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
    p256 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
oldest_due_nonstrict:
    p16 X range:status_completed_retry(410718/22)
    p199 X range:status_completed_retry(410718/1)
    p200 X range:status_completed_retry(410718/1)
    p256 X range:status_completed_retry(410718/1)
## sp-v7
count_partitions:
    p16 X range:status_partition(6012/5999)
    p199 X range:status_partition(190252/153987)
    p200 X ref:status_completed_retry(410758/199980)
    p256 X ref:status_completed_retry(410758/199980)
count_statuses:
    p16 X range:status_partition(384014/200000)
    p199 X range:status_partition(384014/200000)
    p200 X range:status_partition(384014/200000)
    p256 X range:status_partition(384014/200000)
keys_strict:
    p16 X range:status_completed_created_id(408356/199980);ref:key_completed_created_id(1/1)
    p199 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(408356/16);ref:key_completed_created_id(1/1)
keys_nonstrict:
    p16 X range:status_completed_created_id(408356/199980);ref:key_completed_created_id(1/1.333)
    p199 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(408356/12);ref:key_completed_created_id(1/1.200)
oldest_created:
    p16 X range:status_completed_created_id(408356/29)
    p199 X ref:status_completed_created_id(408356/1)
    p200 X ref:status_completed_created_id(408356/1)
    p256 X ref:status_completed_created_id(408356/1)
oldest_due_strict:
    p16 X range:status_completed_retry(410718/22);ref:key_completed_created_id(1/2000)
    p199 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
    p200 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
    p256 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
oldest_due_nonstrict:
    p16 X range:status_completed_retry(410718/22)
    p199 X range:status_completed_retry(410718/1)
    p200 X range:status_completed_retry(410718/1)
    p256 X range:status_completed_retry(410718/1)
## spc-v7
count_partitions:
    p16 X range:status_partition_created(6012/5999)
    p199 X range:status_partition_created(190218/153987)
    p200 X ref:status_completed_retry(410758/199980)
    p256 X ref:status_completed_retry(410758/199980)
count_statuses:
    p16 X range:status_partition_created(379088/200000)
    p199 X range:status_partition_created(379088/200000)
    p200 X range:status_partition_created(379088/200000)
    p256 X range:status_partition_created(379088/200000)
keys_strict:
    p16 X range:status_completed_created_id(408356/199980);ref:key_completed_created_id(1/1)
    p199 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(408356/16);ref:key_completed_created_id(1/1)
keys_nonstrict:
    p16 X range:status_completed_created_id(408356/199980);ref:key_completed_created_id(1/1.333)
    p199 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p200 X ref:status_completed_created_id(408356/20);ref:key_completed_created_id(1/1)
    p256 X ref:status_completed_created_id(408356/12);ref:key_completed_created_id(1/1.200)
oldest_created:
    p16 X range:status_completed_created_id(408356/29)
    p199 X ref:status_completed_created_id(408356/1)
    p200 X ref:status_completed_created_id(408356/1)
    p256 X ref:status_completed_created_id(408356/1)
oldest_due_strict:
    p16 X range:status_completed_retry(410718/22);ref:key_completed_created_id(1/2000)
    p199 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
    p200 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
    p256 X range:status_completed_retry(410718/1);ref:key_completed_created_id(1/2000)
oldest_due_nonstrict:
    p16 X range:status_completed_retry(410718/22)
    p199 X range:status_completed_retry(410718/1)
    p200 X range:status_completed_retry(410718/1)
    p256 X range:status_completed_retry(410718/1)
```
Reading: the 4th index is chosen only by `count_partitions` and `count_statuses` (p16: 6012 vs 5999, p199: 190252 vs 153987; the same estimate error pattern as the reference indexes, never an order-of-magnitude one). At 200 and 256 partitions `count_partitions` estimates 402955 for 199980 actual (2x over, harmless) and `sp` uses it while `spc` (and `sp` on v7) falls back to the covering `status_completed_retry` scan, which explains why the full-ownership S5 gain exists only for `sp` v4. All `keys_*`, `oldest_*` plans use `status_completed_created_id` / `status_completed_retry` / the key index exactly as the reference: no false-estimate plan (the round-1a R1 failure: 256-row estimate on `(status, completed_at, partition_no)`) occurs for either 4th index, on v4 or v7.

## 7. Part B - `kn-05-head` with non-due heads (v4, S5, base DDL, `keys_nonstrict` only; strict dropped by user decision)

For every hot key (100 keys with 2000 NEW) the K oldest NEW records got `next_retry_at = T0 + 1 h`, then `ANALYZE TABLE`, bench without `--prepare` (non-due NEW rows after the update: 1000 / 10 000 / 100 000 for K = 10 / 100 / 1000, including the 20 initial future heads). Variants: `r2-ndK-head` (`kn-05-head`) and `r2-ndK-base` (branch SQL `kn-00-base`), medians in ms, Handler_read_key / Handler_read_next in brackets. Results `results/r2/r2-ndK-{head,base}-v4/mariadb/{S8-S5-ndK,S5-ndK}/`.

| K | label | kn-05-head | kn-00-base | ratio |
|---|---|---|---|---|
| 10 | S8-S5 | 0.82 (40 / 1254) | 7.59 (71 / 1516) | 0.11 |
| 10 | S5 | 0.95 (24 / 788) | 4.39 (33 / 851) | 0.22 |
| 100 | S8-S5 | 3.40 (83 / 11169) | 18.20 (181 / 19197) | 0.19 |
| 100 | S5 | 3.71 (34 / 9829) | 11.08 (71 / 12341) | 0.33 |
| 1000 | S8-S5 | 517 (159 / 110154) | 818 (426 / 325171) | 0.63 |
| 1000 | S5 | 699 (113 / 106799) | 874 (271 / 231799) | 0.80 |

Plans (K = 1000, S5): both forms scan `status_completed_created_id` (est. 398748, actual 96800 rows) and probe the key index per candidate; `kn-05-head` probes 10 candidates reading about 1000 index rows each (10 080 examined), `kn-00-base` probes 135 candidates (199 568 examined) before it finds 10 keys. **Verdict: `kn-05-head` stays below `kn-00-base` at every K and both ownership levels; no crossover.** It is not cheap: the cost grows linearly with K (about 0.5-0.7 s at K = 1000, 100 000 non-due rows, 50 % of the NEW backlog) because the outer scan has to pass all non-due candidates; the branch form does the same scan and pays more per extra candidate. Nothing to fix with a form change in round 2; K = 1000 (50 % of each key non-due) is an extreme case.

## 8. Part C - equivalence

`bin/bench equiv` against `mdb-opt` (reports in `results/r2/equiv-{v4,v7}/equiv/mariadb/<scenario>/`):

| Pack / DDL | S1 | S5 | S7 |
|---|---|---|---|
| forms-a, base DDL, v4 | 28 PASS | 26 PASS, 2 SKIP | - |
| forms-a, `sp` DDL, v4 | 28 PASS | - | - |
| forms-a, `spc` DDL, v4 | 28 PASS | - | - |
| forms-a, base DDL, v7 data | 28 PASS | 26 PASS, 2 SKIP | - |
| forms-e5h (`ks-11`, `kn-14`, `oc-08`, `odn-06`, `ods-09`) on `b-e5l` DDL, v4 | 28 PASS | 26 PASS, 2 SKIP | 26 PASS, 2 SKIP |

No WARN, FAIL or ERROR. (The 2 SKIP on S5/S7 are the admin finders that run only on S1/S3.)

## 9. Ranking

1. `spc` `(status, partition_no, created_at)` - cheapest 4th index in v4; ties `sp` in v7 on write cost and keeps S10 at the reference.
2. `sp` `(status, partition_no)` - v4: +144 % insert redo on S4, 2.1x completion and 4x delete redo on S2, S10 -14-17 %; v7: close to `spc`, S10 -8 %, slightly smaller (index 62 vs 66 MB on S2).
3. No 4th index (reference) - `count_partitions` stays 106-115 ms at partial ownership (not accepted by the user decision).

The id version does not change the ranking, but UUIDv7 lowers all write costs and doubles S10 throughput by itself, and removes the `sp` insert penalty.

## 10. Deferred-test candidates

* P1 `keys_*` / `oldest_*` +0.05-0.25 ms from the 4th index (relative 14-140 %, absolute small): repeat on a quiet host and isolate the cause (planning time vs execution: compare with `EXPLAIN` timing or a 3-index schema with a dummy unused 4th index).
* S2-wc completion/delete redo (1.7-3.7x) of the 4th index: confirm with a longer run; the S4-wc numbers (large backlog) show no completion penalty in v4.
* Full-ownership `count_partitions` (S4, S5): no gain with `spc`; relevant only if single-instance deployments with a big NEW backlog matter.
* S9 (churn) with the winner, both id versions.

## 11. Proposed confirm list (winning id version: v7, the planned release id generation; keep v4 as a robustness check for candidate 1)

1. Candidate 1: (a) = i1, i2, i3 + `spc`, forms-a overlays (`ks-05-head`, `kn-05-head`, `oc-03-order-id`, `odn-01-order`).
2. Candidate 2: `b-e5l` DDL + `spc` + `forms-e5h` (`ks-11`, `kn-14`, `oc-08`, `odn-06`, `ods-09`); equivalence PASS already shown on S1, S5, S7 without the 4th index.
3. Labels: all, plus S2-wc / S4-wc, S9, S10; reference `mdb-main` and `mdb-opt`; reversed second pass.
