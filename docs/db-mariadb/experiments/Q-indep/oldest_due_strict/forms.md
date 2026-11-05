# oldest_due_strict: proposed forms (independent proposer)

## ne-ord
- **Idea:** ORDER BY next_retry_at LIMIT :limit with NOT EXISTS (equals the branch SQL; mandatory ORDER BY form).
- **Why it may be faster:** Ordered scan of idx3 stops at the first due row that passes the predecessor probe.
- **Access path needed:** Outer idx_outbox_record_status_completed_retry; inner idx_outbox_record_key_completed_created_id.
- **Semantic risks:** Zero rows when none eligible.
- **MySQL portability:** Valid MySQL 8.

## ne-min
- **Idea:** MIN form with NOT EXISTS (mandatory MIN form).
- **Why it may be faster:** No early stop: the probe runs for every due row, so cost grows with the due backlog. Comparison point.
- **Access path needed:** Outer idx_outbox_record_status_completed_retry; inner idx_outbox_record_key_completed_created_id.
- **Semantic risks:** Single row, NULL when none.
- **MySQL portability:** Valid MySQL 8.

## split-ord
- **Idea:** Two NOT EXISTS (older created_at / tie with smaller id).
- **Why it may be faster:** Pure ref+range probes without OR.
- **Access path needed:** Outer idx_outbox_record_status_completed_retry; inner idx_outbox_record_key_completed_created_id.
- **Semantic risks:** Two probes per candidate.
- **MySQL portability:** Valid MySQL 8.

## split-min
- **Idea:** MIN with the split predecessor test.
- **Why it may be faster:** Comparison with split-ord.
- **Access path needed:** Outer idx_outbox_record_status_completed_retry; inner idx_outbox_record_key_completed_created_id.
- **Semantic risks:** As ne-min.
- **MySQL portability:** Valid MySQL 8.

## lj-ord
- **Idea:** LEFT JOIN anti-join with STRAIGHT_JOIN, ordered by next_retry_at.
- **Why it may be faster:** Not-exists join optimization with `o` driving the next_retry_at-ordered scan.
- **Access path needed:** Outer idx_outbox_record_status_completed_retry; inner idx_outbox_record_key_completed_created_id.
- **Semantic risks:** Same rows (earlier.id PK, NOT NULL).
- **MySQL portability:** Valid MySQL 8.

## win-min
- **Idea:** Head-of-key via ROW_NUMBER over incomplete rows, then MIN of the due eligible heads.
- **Why it may be faster:** Single pass instead of probing per due row; good when the incomplete set is small.
- **Access path needed:** idx_outbox_record_key_completed_created_id / idx_outbox_record_status_completed_created_id full incomplete scan + window sort.
- **Semantic risks:** Same value. Cost linear in the incomplete set (bad at S4/S6). Control form.
- **MySQL portability:** Valid MySQL 8.
