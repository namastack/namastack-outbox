# pages: proposed forms (independent proposer)

## late-join
- **Idea:** Deferred join: first collect (id, record_key, created_at) from the covering secondary index, sort the narrow tuples, then fetch full rows (with payload/context) by PK; status is checked on the PK row.
- **Why it may be faster:** Avoids sorting wide rows (LONGTEXT payload in the sort buffer) and makes the PK lookups follow a narrow, ordered key list. Mostly a gain with larger pages (S4-b100, S5 2000 rows/key).
- **Access path needed:** idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id) is covering for the inner; PK eq_ref for the outer.
- **Semantic risks:** Same rows/order: status is checked on r (not in B-idx2). ORDER BY uses k.* columns (equal to r.*). Optimizer may still join then filesort wide rows.
- **MySQL portability:** Valid MySQL 8.

## force-idx2
- **Idea:** Pin the key index so each key is a ref range delivered already in (created_at, id) order.
- **Why it may be faster:** Avoids an alternative plan through idx1 (status prefix) that would scan all NEW rows for a 1M backlog.
- **Access path needed:** idx_outbox_record_key_completed_created_id.
- **Semantic risks:** HIGH RISK: FORCE INDEX fails on a schema without idx_outbox_record_key_completed_created_id. With multiple keys, ORDER BY record_key,... still may need a filesort across the ranges.
- **MySQL portability:** Valid syntax; index must exist.

## semi-idx1
- **Idea:** Resolve the status filter inside the index by reading idx1 (status, completed_at, ..., record_key) covering, then fetch rows by PK.
- **Why it may be faster:** Evaluates the status predicate on the index, never touching PK rows of FAILED records of the key.
- **Access path needed:** idx_outbox_record_status_completed_created_id: prefix (status, completed_at IS NULL) range scan filtered by record_key IN (...) in the index (no seek by key!).
- **Semantic risks:** Reads every NEW/FAILED index entry of the table-wide incomplete set, so cost is proportional to the backlog (bad for S4/S6) -- include as a deliberate counter-example; a semi-join may be converted to a key-lookup plan though.
- **MySQL portability:** Valid MySQL 8.

## json-table (needs code change)
- **Idea:** Derived key table from a JSON array; each key is a nested-loop ref lookup into idx2.
- **Why it may be faster:** Gives the optimizer a real driving table (one ref per key, in key order) so ORDER BY record_key can be satisfied by the merge of ordered ranges; avoids multi-range IN planning.
- **Access path needed:** idx_outbox_record_key_completed_created_id.
- **Semantic risks:** NEEDS CODE CHANGE: binds a single JSON string parameter :recordKeysJson (e.g. ["k1","k2"]) instead of the expanded :recordKeys. Collation: JSON_TABLE column uses the connection/default collation, same as record_key in normal installs; check comparisons under a different default collation.
- **MySQL portability:** JSON_TABLE exists in MySQL 8 (syntax equal).

## values-cte (needs code change)
- **Idea:** Table value constructor as derived key table (CTE).
- **Why it may be faster:** Same idea as json-table without JSON parsing.
- **Access path needed:** idx_outbox_record_key_completed_created_id.
- **Semantic risks:** NEEDS CODE CHANGE: :recordKeysRows must expand to `(?),(?),...` (one parenthesized row per key), the harness expands `?,?,...`. MariaDB VALUES column naming via CTE column list; TVC duplicates keys if the set had duplicates (a Set cannot).
- **MySQL portability:** MySQL 8 uses `VALUES ROW(?), ROW(?)`: not portable as written.

## union-per-key (needs code change)
- **Idea:** One equality ref probe per key combined with UNION ALL (shown for 2 keys; one branch per key).
- **Why it may be faster:** Each branch is an equality ref range on idx2 already ordered (created_at, id); no multi-range planning.
- **Access path needed:** idx_outbox_record_key_completed_created_id.
- **Semantic risks:** NEEDS CODE CHANGE: statement text is generated per key count with parameters :recordKey1..:recordKeyN. UNION ALL materializes in a temp table then sorts. Statement cache churn for varying key counts. Test file only checks syntax with 2 keys.
- **MySQL portability:** Valid MySQL 8.
