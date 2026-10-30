# H60 per-query matrix (Phase 2, Postgres 18, tmpfs, scenarios A/B/D, C/E hot path only)

Baseline = `results/base-<S>`; S1 = H08a + extended statistics; S2 = S1 + U2b. Rewritten queries from `experiments/H60/queries/`
for S1 and S2. All times are medians of 3 warm runs (ms), custom plans unless stated. Key queries (`findRecordKeysInPartitions_*`) are
shown in serial mode (pgjdbc `withMaxRows` disables parallel workers; also used for the baseline cleanup selects). The rewritten cleanup selects
carry a real SQL LIMIT and have no `withMaxRows`, so their S1/S2 numbers are plain custom plans; the baseline column is the single baseline measurement
(serial, cutoff = 10th percentile, variant `default`), so the cleanup rows compare one baseline value against four cutoffs.
Raw data: `results/base-*`, `results/H60-S1-*`, `results/H60-S2-*`; run logs `results/H60-run-*.log`. Index used per query: see the plan table at the end
(S1; in S2 `findIncompleteRecordPages`/`findIncompleteRecordsByRecordKey` use `record_key_completed_created_id` instead, otherwise identical).

Notes
- Hot-path queries that use `withMaxRows` today: both key queries. Rewritten form carries `LIMIT :batch`; in custom plans the result is the same with or
  without `withMaxRows`. In forced generic plans without `withMaxRows` (parallel workers allowed) D regresses to 25/145 ms (64/256 partitions) with a
  Gather Merge plan that does not stop early; serial 0.44/0.54 ms. Keep `withMaxRows(batch)` next to the SQL LIMIT, or ensure no parallel plan (finding F-2 in H60).
- Admin/periodic queries with unchanged text and index (`findPendingRecords`, `findCompletedRecords`, `deleteByStatus(COMPLETED)`, `countByStatus`, counts) keep their baseline plans (seq scan or index-only scan on `status`).
  They are not on the hot path; `findPendingRecords` B 213 ms and `deleteByStatus(COMPLETED)` 617-780 ms are unchanged.

## Per-query matrix, custom plans, ms

| query | variant | base A | base B | base D | S1 A | S1 B | S1 D | S2 A | S2 B | S2 D |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| countByStatus | status=COMPLETED | 23.9 | 19.6 | 22.8 | 23.4 | 19.6 | 22.7 | 23.4 | 19.4 | 22.9 |
| countByStatus | status=FAILED | 0.04 | 0.041 | 0.039 | 0.042 | 0.039 | 0.032 | 0.041 | 0.041 | 0.032 |
| countByStatus | status=NEW | 0.326 | 9.85 | 1 | 0.333 | 9.9 | 1.06 | 0.323 | 9.59 | 1.05 |
| countRecordsByPartition | default | 0.033 | 0.103 | 0.036 | 0.034 | 0.092 | 0.034 | 0.034 | 0.087 | 0.031 |
| countRecordsByPartitions | parts=256 | 1.15 | 16.8 | 2.23 | 1.12 | 17.0 | 2.61 | 1.1 | 17.2 | 2.26 |
| countRecordsByPartitions | parts=64 | 0.32 | 4.17 | 0.458 | 0.276 | 4.05 | 0.5 | 0.305 | 4.15 | 0.46 |
| deleteById | default | 0.038 | 0.039 | 0.046 | 0.052 | 0.041 | 0.046 | 0.038 | 0.039 | 0.04 |
| deleteByIds | default | 3.15 | 2.96 | 3.07 | 3.13 | 3.11 | 3.04 | 3.05 | 3.22 | 2.94 |
| deleteByRecordKeyAndStatus | default | 0.266 | 0.067 | 1.13 | 0.249 | 0.101 | 1.14 | 0.242 | 0.056 | 1.14 |
| deleteByStatus | status=COMPLETED | 737 | 620 | 752 | 780 | 617 | 750 | 747 | 626 | 726 |
| deleteByStatus | status=FAILED | 0.124 | 0.136 | 0.047 | 0.155 | 0.132 | 0.041 | 0.162 | 0.138 | 0.041 |
| deleteCompletedRecords_selectIds | cutpct=0.1 | 170 | 159 | 157 | 75.9 | 74.5 | 71.8 | 77.3 | 74.2 | 73.3 |
| deleteCompletedRecords_selectIds | cutpct=0.5 | 170 | 159 | 157 | 93.5 | 87.2 | 90.0 | 94.0 | 88.8 | 89.8 |
| deleteCompletedRecords_selectIds | cutpct=0.9 | 170 | 159 | 157 | 109 | 100 | 106 | 111 | 101 | 107 |
| deleteCompletedRecords_selectIds | cutpct=1.0 | 170 | 159 | 157 | 113 | 104 | 111 | 114 | 104 | 112 |
| deleteFailedRecords_selectIds | default | 0.076 | 0.077 | 0.027 | 0.084 | 0.083 | 0.041 | 0.085 | 0.129 | 0.031 |
| findCompletedRecords | default | 888 | 710 | 849 | 874 | 710 | 853 | 865 | 718 | 847 |
| findCompletedRecordsBefore | cutpct=0.1 | 273 | 211 | 253 | 77.0 | 78.3 | 73.1 | 77.2 | 74.8 | 75.6 |
| findCompletedRecordsBefore | cutpct=0.5 | 273 | 211 | 253 | 89.6 | 85.2 | 85.4 | 90.3 | 84.8 | 87.4 |
| findCompletedRecordsBefore | cutpct=0.9 | 273 | 211 | 253 | 103 | 95.7 | 99.8 | 104 | 96.5 | 102 |
| findCompletedRecordsBefore | cutpct=1.0 | 273 | 211 | 253 | 108 | 99.0 | 104 | 107 | 98.9 | 106 |
| findFailedRecords | default | 0.157 | 0.153 | 0.046 | 0.157 | 0.146 | 0.037 | 0.167 | 0.154 | 0.039 |
| findFailedRecordsBefore | default | 0.119 | 0.109 | 0.041 | 0.127 | 0.125 | 0.047 | 0.134 | 0.14 | 0.029 |
| findIncompleteRecordPages | keys=10 | 0.31 | 0.181 | 3.87 | 0.108 | 0.151 | 2.39 | 0.094 | 0.11 | 0.846 |
| findIncompleteRecordPages | keys=100 | 0.836 | 6.63 | 39.7 | 0.517 | 0.77 | 22.7 | 0.383 | 0.59 | 8.82 |
| findIncompleteRecordPages | keys=1000 | 4.04 | 22.0 | 39.6 | 4.71 | 8.05 | 22.2 | 4.06 | 6.44 | 9.19 |
| findIncompleteRecordsByRecordKey | default | 0.28 | 0.046 | 1.06 | 0.278 | 0.047 | 1.07 | 0.237 | 0.045 | 1.05 |
| findOldestCreatedAtByPartitions | parts=256 | 1.23 | 50.1 | 4.78 | 0.072 | 0.071 | 0.047 | 0.073 | 0.072 | 0.046 |
| findOldestCreatedAtByPartitions | parts=64 | 1 | 49.6 | 4.55 | 0.067 | 0.065 | 0.033 | 0.06 | 0.06 | 0.034 |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | 15.7 | 214 | 139 | 0.309 | 0.36 | 0.092 | 0.359 | 0.323 | 0.092 |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | 4.72 | 165 | 38.8 | 0.176 | 0.153 | 0.057 | 0.161 | 0.163 | 0.067 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | 0.041 | 0.046 | 0.035 | 0.037 | 0.043 | 0.036 | 0.041 | 0.046 | 0.038 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | 0.036 | 0.043 | 0.033 | 0.036 | 0.042 | 0.033 | 0.036 | 0.041 | 0.033 |
| findPendingRecords | default | 3.13 | 212 | 22.8 | 3.16 | 215 | 22.5 | 3.18 | 217 | 23.1 |
| findRecordKeysInPartitions_filter | parts=256 | 19.2 | 367 | 141 | 0.343 | 0.38 | 0.11 | 0.4 | 0.342 | 0.108 |
| findRecordKeysInPartitions_filter | parts=64 | 5.05 | 265 | 38.1 | 0.217 | 0.209 | 0.087 | 0.211 | 0.224 | 0.165 |
| findRecordKeysInPartitions_nofilter | parts=256 | 2.36 | 75.6 | 6.41 | 0.209 | 0.134 | 0.103 | 0.139 | 0.141 | 0.104 |
| findRecordKeysInPartitions_nofilter | parts=64 | 1.31 | 56.4 | 4.54 | 0.145 | 0.134 | 0.09 | 0.13 | 0.137 | 0.093 |
| insert | default | 0.039 | 0.044 | 0.034 | 0.046 | 0.033 | 0.041 | 0.035 | 0.027 | 0.044 |
| update | default | 0.065 | 0.092 | 0.083 | 0.087 | 0.063 | 0.086 | 0.069 | 0.072 | 0.104 |
| updateProcessingState | default | 0.089 | 0.071 | 0.091 | 0.064 | 0.075 | 0.092 | 0.069 | 0.072 | 0.083 |

## generic plans, ms

| query | variant | base A | base B | base D | S1 A | S1 B | S1 D | S2 A | S2 B | S2 D |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| countByStatus | status=COMPLETED | 82.1 | 67.1 | 81.6 | 81.8 | 67.0 | 81.2 | 83.6 | 67.5 | 83.4 |
| countByStatus | status=FAILED | 5.99 | 5.69 | 6.44 | 5.79 | 5.82 | 5.67 | 5.8 | 5.8 | 5.85 |
| countByStatus | status=NEW | 5.91 | 8.4 | 5.75 | 5.84 | 8.42 | 5.7 | 5.89 | 8.26 | 5.89 |
| countRecordsByPartitions | parts=256 | 1.15 | 16.9 | 3.06 | 1.11 | 17.2 | 3.13 | 1.16 | 17.2 | 3.01 |
| countRecordsByPartitions | parts=64 | 0.322 | 4.16 | 0.728 | 0.341 | 4.33 | 0.776 | 0.339 | 4.1 | 0.727 |
| deleteById | default | 0.033 | 0.034 | 0.039 | 0.032 | 0.036 | 0.032 | 0.036 | 0.032 | 0.033 |
| deleteCompletedRecords_selectIds | cutpct=0.1 | - | - | - | 101 | 82.9 | 99.3 | 103 | 84.2 | 101 |
| deleteCompletedRecords_selectIds | cutpct=0.5 | - | - | - | 115 | 93.1 | 114 | 117 | 96.6 | 115 |
| deleteCompletedRecords_selectIds | cutpct=0.9 | - | - | - | 129 | 106 | 128 | 131 | 106 | 131 |
| deleteCompletedRecords_selectIds | cutpct=1.0 | - | - | - | 132 | 108 | 131 | 135 | 108 | 134 |
| deleteFailedRecords_selectIds | default | - | - | - | 0.076 | 0.061 | 0.026 | 0.049 | 0.066 | 0.029 |
| findCompletedRecordsBefore | cutpct=0.1 | - | - | - | 104 | 84.2 | 102 | 105 | 86.8 | 104 |
| findCompletedRecordsBefore | cutpct=0.5 | - | - | - | 122 | 99.8 | 123 | 126 | 101 | 123 |
| findCompletedRecordsBefore | cutpct=0.9 | - | - | - | 142 | 115 | 141 | 143 | 117 | 142 |
| findCompletedRecordsBefore | cutpct=1.0 | - | - | - | 144 | 118 | 147 | 148 | 120 | 147 |
| findFailedRecordsBefore | default | - | - | - | 0.064 | 0.048 | 0.042 | 0.055 | 0.068 | 0.03 |
| findIncompleteRecordPages | keys=10 | 0.194 | 0.198 | 6.36 | 0.151 | 0.144 | 2.41 | 0.106 | 0.117 | 0.817 |
| findIncompleteRecordPages | keys=100 | 0.889 | 6.59 | 43.8 | 0.501 | 0.783 | 21.3 | 0.455 | 0.591 | 9.12 |
| findIncompleteRecordPages | keys=1000 | 11.4 | 22.1 | 43.9 | 5.15 | 8.38 | 21.8 | 4.45 | 6.98 | 9.21 |
| findIncompleteRecordsByRecordKey | default | 0.047 | 0.05 | 0.789 | 0.046 | 0.047 | 0.762 | 0.05 | 0.056 | 0.794 |
| findOldestCreatedAtByPartitions | parts=256 | 11.1 | 212 | 27.3 | 0.118 | 0.111 | 0.081 | 0.11 | 0.105 | 0.082 |
| findOldestCreatedAtByPartitions | parts=64 | 7.41 | 78.9 | 12.5 | 0.121 | 0.08 | 0.055 | 0.081 | 0.084 | 0.057 |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | 58.0 | 329 | 76.6 | 0.674 | 0.677 | 0.146 | 0.715 | 0.637 | 0.144 |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | 10.4 | 122 | 26.5 | 0.259 | 0.277 | 0.089 | 0.311 | 0.258 | 0.108 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | 0.084 | 0.089 | 0.081 | 0.082 | 0.092 | 0.081 | 0.079 | 0.087 | 0.079 |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | 0.059 | 0.047 | 0.041 | 0.048 | 0.057 | 0.044 | 0.052 | 0.055 | 0.045 |
| findRecordKeysInPartitions_filter | parts=256 | 49.5 | 872 | 195 | 0.757 | 1.12 | 0.559 | 0.789 | 1.14 | 0.192 |
| findRecordKeysInPartitions_filter | parts=64 | 38.9 | 232 | 54.2 | 0.344 | 0.678 | 0.447 | 0.36 | 0.644 | 0.158 |
| findRecordKeysInPartitions_nofilter | parts=256 | 19.5 | 624 | 59.8 | 0.198 | 0.624 | 0.541 | 0.196 | 0.614 | 0.599 |
| findRecordKeysInPartitions_nofilter | parts=64 | 6.79 | 27.3 | 20.3 | 0.282 | 0.515 | 0.439 | 0.213 | 0.438 | 0.476 |
| insert | default | 0.052 | 0.044 | 0.039 | 0.051 | 0.049 | 0.043 | 0.102 | 0.037 | 0.042 |
| updateProcessingState | default | 0.098 | 0.081 | 0.11 | 0.085 | 0.089 | 0.093 | 0.068 | 0.07 | 0.115 |

## hot path vs best isolated Phase 1 number

| query | variant | scen | baseline | best isolated | S1 | S2 | S1 vs iso | S2 vs iso | S1 vs base |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| findRecordKeysInPartitions_filter | parts=64 | A | 5.05 | 0.187 | 0.217 | 0.211 | +16% | +13% | -96% |
| findRecordKeysInPartitions_filter | parts=64 | B | 265 | 0.203 | 0.209 | 0.224 | +3% | +10% | -100% |
| findRecordKeysInPartitions_filter | parts=64 | D | 38.1 | 0.089 | 0.087 | 0.165 | -2% | +85% | -100% |
| findRecordKeysInPartitions_filter | parts=256 | A | 19.2 | 0.379 | 0.343 | 0.4 | -9% | +6% | -98% |
| findRecordKeysInPartitions_filter | parts=256 | B | 367 | 0.383 | 0.38 | 0.342 | -1% | -11% | -100% |
| findRecordKeysInPartitions_filter | parts=256 | D | 141 | 0.106 | 0.11 | 0.108 | +4% | +2% | -100% |
| findRecordKeysInPartitions_nofilter | parts=64 | A | 1.31 | 0.115 | 0.145 | 0.13 | +26% | +13% | -89% |
| findRecordKeysInPartitions_nofilter | parts=64 | B | 56.4 | 0.116 | 0.134 | 0.137 | +16% | +18% | -100% |
| findRecordKeysInPartitions_nofilter | parts=64 | D | 4.54 | 0.085 | 0.09 | 0.093 | +6% | +9% | -98% |
| findRecordKeysInPartitions_nofilter | parts=256 | A | 2.36 | 0.12 | 0.209 | 0.139 | +74% | +16% | -91% |
| findRecordKeysInPartitions_nofilter | parts=256 | B | 75.6 | 0.127 | 0.134 | 0.141 | +6% | +11% | -100% |
| findRecordKeysInPartitions_nofilter | parts=256 | D | 6.41 | 0.1 | 0.103 | 0.104 | +3% | +4% | -98% |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | A | 4.72 | 0.156 | 0.176 | 0.161 | +13% | +3% | -96% |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | B | 165 | 0.169 | 0.153 | 0.163 | -9% | -4% | -100% |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | D | 38.8 | 0.054 | 0.057 | 0.067 | +6% | +24% | -100% |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | A | 15.7 | 0.295 | 0.309 | 0.359 | +5% | +22% | -98% |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | B | 214 | 0.324 | 0.36 | 0.323 | +11% | -0% | -100% |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | D | 139 | 0.078 | 0.092 | 0.092 | +18% | +18% | -100% |
| findIncompleteRecordPages | keys=10 | A | 0.31 | 0.115 | 0.108 | 0.094 | -6% | +7% | -65% |
| findIncompleteRecordPages | keys=10 | B | 0.181 | 0.139 | 0.151 | 0.11 | +9% | -10% | -17% |
| findIncompleteRecordPages | keys=10 | D | 3.87 | 2.38 | 2.39 | 0.846 | +1% | +1% | -38% |
| findIncompleteRecordPages | keys=100 | A | 0.836 | 0.512 | 0.517 | 0.383 | +1% | -2% | -38% |
| findIncompleteRecordPages | keys=100 | B | 6.63 | 0.755 | 0.77 | 0.59 | +2% | -6% | -88% |
| findIncompleteRecordPages | keys=100 | D | 39.7 | 22.0 | 22.7 | 8.82 | +3% | -1% | -43% |
| findIncompleteRecordPages | keys=1000 | A | 4.04 | 4.84 | 4.71 | 4.06 | -3% | +2% | +16% |
| findIncompleteRecordPages | keys=1000 | B | 22.0 | 7.87 | 8.05 | 6.44 | +2% | -9% | -63% |
| findIncompleteRecordPages | keys=1000 | D | 39.6 | 22.0 | 22.2 | 9.19 | +1% | +5% | -44% |
| findOldestCreatedAtByPartitions | parts=64 | A | 1 | 0.049 | 0.067 | 0.06 | +37% | +22% | -93% |
| findOldestCreatedAtByPartitions | parts=64 | B | 49.6 | 0.046 | 0.065 | 0.06 | +41% | +30% | -100% |
| findOldestCreatedAtByPartitions | parts=64 | D | 4.55 | 0.032 | 0.033 | 0.034 | +3% | +6% | -99% |
| findOldestCreatedAtByPartitions | parts=256 | A | 1.23 | 0.06 | 0.072 | 0.073 | +20% | +22% | -94% |
| findOldestCreatedAtByPartitions | parts=256 | B | 50.1 | 0.06 | 0.071 | 0.072 | +18% | +20% | -100% |
| findOldestCreatedAtByPartitions | parts=256 | D | 4.78 | 0.048 | 0.047 | 0.046 | -2% | -4% | -99% |

Flags (>10% and >0.05 ms):
- findRecordKeysInPartitions_filter parts=64 D: S2 vs iso 0.089 -> 0.165 ms
- findRecordKeysInPartitions_nofilter parts=256 A: S1 vs iso 0.12 -> 0.209 ms
- findOldestDueNextRetryAtByPartitions_filter parts=256 A: S2 vs iso 0.295 -> 0.359 ms
- findIncompleteRecordPages keys=1000 A: S1 vs base 4.04 -> 4.71 ms

## C and E, hot path

| query | variant | base C | S1 C | base E | S1 E |
|---|---|---:|---:|---:|---:|
| findRecordKeysInPartitions_filter | parts=64 | 132 | 0.215 | 5.1 | 0.392 |
| findRecordKeysInPartitions_filter | parts=256 | 208 | 0.41 | 17.8 | 0.893 |
| findRecordKeysInPartitions_nofilter | parts=64 | 29.6 | 0.136 | 1.38 | 0.155 |
| findRecordKeysInPartitions_nofilter | parts=256 | 76.8 | 0.143 | 2.46 | 0.165 |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | 65.8 | 0.15 | 4.71 | 0.316 |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | 83.8 | 0.305 | 14.7 | 0.787 |
| findIncompleteRecordPages | keys=10 | 0.116 | 0.107 | 0.309 | 0.123 |
| findIncompleteRecordPages | keys=100 | 0.679 | 0.509 | 0.82 | 0.555 |
| findIncompleteRecordPages | keys=1000 | 12.1 | 5.05 | 4.08 | 4.76 |
| findOldestCreatedAtByPartitions | parts=64 | 19.9 | 0.058 | 1.13 | 0.106 |
| findOldestCreatedAtByPartitions | parts=256 | 25.6 | 0.077 | 1.14 | 0.118 |

## hot path sum (key filter 64p + oldest due filter 64p + pages 100 keys + oldest created 64p), ms

| set | A | B | D | B vs base B |
|---|---:|---:|---:|---:|
| base | 11.61 | 487.06 | 121.22 | +0.0% |
| S1 | 0.98 | 1.20 | 22.89 | -99.8% |
| S2 | 0.81 | 1.04 | 9.09 | -99.8% |

## plan shape and indexes (S1)

| query | variant | S1 A shape | S1 B shape | S1 D shape | S1 B indexes |
|---|---|---|---|---|---|
| countByStatus | status=COMPLETED | Finalize Aggregate(Gather(Partial Aggregate(Parallel IOS))) | Finalize Aggregate(Gather(Partial Aggregate(Parallel IOS))) | Finalize Aggregate(Gather(Partial Aggregate(Parallel IOS))) | status |
| countByStatus | status=FAILED | Aggregate(IOS) | Aggregate(IOS) | Aggregate(IOS) | status |
| countByStatus | status=NEW | Aggregate(IOS) | Aggregate(IOS) | Aggregate(IOS) | status |
| countRecordsByPartition | default | Aggregate(IOS) | Aggregate(IOS) | Aggregate(IOS) | partition_status_retry |
| countRecordsByPartitions | parts=256 | Aggregate(IS) | Aggregate(IOS) | Aggregate(IOS) | partition_status_retry |
| countRecordsByPartitions | parts=64 | Aggregate(IOS) | Aggregate(IOS) | Aggregate(IOS) | partition_status_retry |
| deleteById | default | Delete(IS) | Delete(IS) | Delete(IS) | pkey |
| deleteByIds | default | Delete(BHS(Bitmap IS)) | Delete(BHS(Bitmap IS)) | Delete(BHS(Bitmap IS)) | pkey |
| deleteByRecordKeyAndStatus | default | Delete(BHS(BitmapAnd(Bitmap IS,Bitmap IS))) | Delete(IS) | Delete(BHS(BitmapAnd(Bitmap IS,Bitmap IS))) | record_key_completed_created |
| deleteByStatus | status=COMPLETED | Delete(Seq) | Delete(Seq) | Delete(Seq) | - |
| deleteByStatus | status=FAILED | Delete(IS) | Delete(IS) | Delete(IS) | status |
| deleteCompletedRecords_selectIds | cutpct=0.1 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| deleteCompletedRecords_selectIds | cutpct=0.5 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| deleteCompletedRecords_selectIds | cutpct=0.9 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| deleteCompletedRecords_selectIds | cutpct=1.0 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| deleteFailedRecords_selectIds | default | Limit(Sort(IS)) | Limit(Sort(IS)) | Limit(Sort(IS)) | status |
| findCompletedRecords | default | Gather Merge(Sort(PSeq)) | Gather Merge(Sort(PSeq)) | Gather Merge(Sort(PSeq)) | - |
| findCompletedRecordsBefore | cutpct=0.1 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| findCompletedRecordsBefore | cutpct=0.5 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| findCompletedRecordsBefore | cutpct=0.9 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| findCompletedRecordsBefore | cutpct=1.0 | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | Limit(Gather Merge(Sort(PSeq))) | - |
| findFailedRecords | default | Sort(IS) | Sort(IS) | Sort(IS) | status |
| findFailedRecordsBefore | default | Limit(Sort(IS)) | Limit(Sort(IS)) | Limit(Sort(IS)) | status |
| findIncompleteRecordPages | keys=10 | Nested Loop(Sort(Function Scan),Incremental Sort(IS)) | Nested Loop(Sort(Function Scan),Incremental Sort(IS)) | Nested Loop(Sort(Function Scan),Sort(BHS(Bitmap IS))) | record_key_completed_created |
| findIncompleteRecordPages | keys=100 | Nested Loop(Sort(Function Scan),Incremental Sort(IS)) | Nested Loop(Sort(Function Scan),Incremental Sort(IS)) | Nested Loop(Sort(Function Scan),Sort(BHS(Bitmap IS))) | record_key_completed_created |
| findIncompleteRecordPages | keys=1000 | Nested Loop(Sort(Function Scan),Incremental Sort(IS)) | Nested Loop(Sort(Function Scan),Incremental Sort(IS)) | Nested Loop(Sort(Function Scan),Sort(BHS(Bitmap IS))) | record_key_completed_created |
| findIncompleteRecordsByRecordKey | default | Sort(BHS(BitmapAnd(Bitmap IS,Bitmap IS))) | Sort(IS) | Sort(BHS(BitmapAnd(Bitmap IS,Bitmap IS))) | record_key_completed_created |
| findOldestCreatedAtByPartitions | parts=256 | Result(Limit(IOS)) | Result(Limit(IOS)) | Result(Limit(IOS)) | incomplete_created |
| findOldestCreatedAtByPartitions | parts=64 | Result(Limit(IOS)) | Result(Limit(IOS)) | Result(Limit(IOS)) | incomplete_created |
| findOldestDueNextRetryAtByPartitions_filter | parts=256 | Result(Limit(NL Anti(IS,IS))) | Result(Limit(NL Anti(IS,IS))) | Result(Limit(NL Anti(IS,IS))) | status_retry,record_key_completed_created |
| findOldestDueNextRetryAtByPartitions_filter | parts=64 | Result(Limit(NL Anti(IS,IS))) | Result(Limit(NL Anti(IS,IS))) | Result(Limit(NL Anti(IS,IS))) | status_retry,record_key_completed_created |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=256 | Result(Limit(IS)) | Result(Limit(IS)) | Result(Limit(IS)) | status_retry |
| findOldestDueNextRetryAtByPartitions_nofilter | parts=64 | Result(Limit(IS)) | Result(Limit(IS)) | Result(Limit(IS)) | status_retry |
| findPendingRecords | default | Sort(IS) | Gather Merge(Sort(Parallel IS)) | Sort(IS) | status |
| findRecordKeysInPartitions_filter | parts=256 | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | incomplete_created,record_key_completed_created |
| findRecordKeysInPartitions_filter | parts=64 | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | incomplete_created,record_key_completed_created |
| findRecordKeysInPartitions_nofilter | parts=256 | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | incomplete_created,record_key_completed_created |
| findRecordKeysInPartitions_nofilter | parts=64 | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | Limit(NL Anti(IOS,IS)) | incomplete_created,record_key_completed_created |
| insert | default | Insert(Result) | Insert(Result) | Insert(Result) | - |
| update | default | Update(IS) | Update(IS) | Update(IS) | pkey |
| updateProcessingState | default | Update(IS) | Update(IS) | Update(IS) | pkey |

## Write cost (scenario A, 50k INSERT + 50k updateProcessingState, pgbench, 4 clients; WAL bytes/op is the stable signal, +-1%)

| set | insert WAL B/op | update WAL B/op | insert ms/1k | update ms/1k | index pages after (8 KiB) | vs baseline |
|---|---:|---:|---:|---:|---:|---|
| baseline | 2445 | 3324 | 26.0 | 35.0 | 43374 | |
| S1 | 2490 | 3200 | 26.2 | 35.2 | 36564 | ins +1.8% / upd -3.7% WAL, indexes -15.7% |
| S2 | 2568 | 3312 | 26.1 | 34.3 | 45326 | ins +5.0% / upd -0.4% WAL, indexes +4.5% |
| S1 minus `partition_status_retry` | 2336 | 3015 | 24.0 | 34.2 | | ins -6.2% / upd -5.8% vs S1 |
| S1 minus `status_retry` | 2417 | 3077 | 25.5 | 32.1 | | ins -2.9% / upd -3.8% vs S1 |
| S1 minus `status` | 2422 | 3115 | 26.7 | 33.7 | | ins -2.7% / upd -2.7% vs S1 |

Index sizes (fresh seed, MB, `results/H60-S*-A/sizes.txt`): see the run directories; at scale A the covering partial index is 0.6 MB, in B 20 MB; U2b is 131 MB against 68 MB for `record_key_completed_created`.

## Removal tests (S1, one index dropped in place; `results/H60-drop-{A,B}`; ms, custom)

| dropped | what degrades | A | B | verdict |
|---|---|---|---|---|
| `status_retry` | `findOldestDueNextRetryAtByPartitions` filter on/off 64p/256p | 0.17/0.35 -> 3.4/4.5 (filter), 0.034/0.037 -> 0.29/1.2 (off) | 0.15/0.32 -> 36/56 (filter), 0.04 -> 4.6/18.7 (off); generic 232 ms | keep (drives the MIN queries) |
| `partition_status_retry` | `countRecordsByPartition(s)` (deprecated metrics), `countByStatus(COMPLETED)` plan | counts 0.03/0.27/1.1 -> 0.85/1.0/1.1; COMPLETED 23 -> 71; generic 64p 0.35 -> 7.3, 256p 1.1 -> 13 | counts 0.09/4.0/16.7 -> 40/47/49; generic 64p/256p 5 -> 79, 17 -> 211 | keep unless the deprecated partition-count metrics are off; costs 6% WAL |
| `status` | `countByStatus(COMPLETED)` 24 -> 76 (A), 20 -> 33 (B); NEW 0.33 -> 0.46 (A) | | | optional removal only; saves 2.7% WAL; `findFailedRecordsBefore` / `findIncompleteRecordsByRecordKey` / `deleteFailedRecords_selectIds` get faster without it (0.13 -> 0.04) because they then use `status_retry` / the key index |
| `incomplete_created`, `record_key_completed_created` (S1) / `_id` (S2), pkey | not dropped | | | in use: 110 scans (key queries), 8962 / 6080 scans (pages, probes, key anti join), 2425 / 9039 (updates) in the mix |

## Index usage, workload mix with rewritten queries (`workload-mix.sql` in `experiments/H60`; 100 polls, batch 10, 10 scrapes, 1 cleanup)

| index | base A scans | S1 A | S2 A | base B | S1 B | S1 B filter off | S2 B |
|---|---:|---:|---:|---:|---:|---:|---:|
| incomplete_created | | 110 | 110 | | 110 | 110 | 110 |
| partition_status_retry | 640 | 640 | 640 | 640 | 0 (*) | 0 (*) | 640 |
| record_key_completed_created (`_id` in S2) | 47048 | 8962 | 8962 | 1440 | 6080 | 2400 | 6080 |
| record_key_created (dropped) | 0 | | | 0 | | | |
| status | 198 | 31 | 31 | 151 | 41 | 41 | 31 |
| status_retry | 0 | 10 | 10 | 0 | 10 | 10 | 10 |
| pkey | 2425 | 2425 | 2425 | 9039 | 9039 | 9000 | 9039 |

(*) S1 B mix ran after the in-place F1 toggle (DROP STATISTICS, ANALYZE, CREATE STATISTICS, ANALYZE): the re-ANALYZE flipped the 10 `countRecordsByPartitions` scrapes to
`idx_status` (41 vs 31 scans). The suite runs before the toggle used `partition_status_retry` for the same query (see plan table), as in S2-B. It is a planner estimate tie, not a workload difference.
Sequential scans in the mix: 3 per run (was 223 in B at baseline). Not attributed to a statement; none is a hot-path query (hot-path plans contain no Seq Scan in any scenario).
