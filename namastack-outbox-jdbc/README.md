# JDBC read queries and indexes

`JdbcOutboxRecordQueries` contains the SQL used by `JdbcOutboxRecordRepository`.
The statements are shared by PostgreSQL, MySQL, MariaDB, SQL Server, Oracle and H2.
Schema files retain database-specific index syntax.

## Selection and ordering

Strict processing uses three portable SQL forms with the same ordering rule: select a key
only when its earliest incomplete record is eligible. SQL Server and H2 use
window ranking. PostgreSQL, MariaDB and Oracle use an ordered candidate scan with a global
`NOT EXISTS` predecessor check. MySQL enumerates distinct incomplete partition/key groups,
resolves the first incomplete record globally for each key, and then checks its eligibility. Failed, future-dated and differently assigned predecessors
remain barriers in all forms. The ordering is always `created_at, id`.

Non-strict selection ranks eligible records in the requested partitions. Bulk loading
returns selected keys' NEW, incomplete records in `record_key, created_at, id` order,
including future retries. The scheduler and retry checks control processing.

The statements use standard window functions, correlated predicates and aggregates.
Only the row-limit syntax differs (for key lists and single-head lookups): FETCH FIRST, LIMIT, or OFFSET/FETCH. The limit
is applied after eligibility and ordering. Unknown JDBC products and custom JdbcClient
implementations retain the shared window query and JDBC row limits.

The default JdbcClient delegates to Spring and associates dialect detection with the
same datasource used to execute its statements. Detection is lazy; constructing runtime
persistence still opens no connection. There is no new datasource selection or routing API.

Completed-status selection and unlimited (`batchSize = 0`) calls retain their grouped
query semantics. Write SQL, transaction behavior and completion/deletion are unchanged.

## Limits

Limits count **keys**, not records within those keys. JDBC maxRows remains a safety bound;
known engines additionally receive an outer SQL limit. Zero omits both limits. Bulk
loading still splits requests according to the existing 1,000-key contract.

The six-engine integration suite checks strict barriers, non-strict behavior, tie ordering,
positive limits, unlimited reads and repeatable schema initialization. The [performance module](../namastack-outbox-performance-test/README.md) provides reproducible
read and throughput measurements. No extended statistics
or processing-time ANALYZE is required.

## Age metrics

The oldest-creation query remains a shared `MIN(created_at)` with the original
partition and status predicates. It includes future retries and supports NEW, FAILED
and COMPLETED records; it returns null when nothing matches.

| Database               | Age index                                     | Purpose                                                                                       |
|------------------------|-----------------------------------------------|-----------------------------------------------------------------------------------------------|
| PostgreSQL | `(status, created_at, id) INCLUDE (record_key, partition_no, next_retry_at, completed_at)` | Covers bounded strict polling, including equal creation times, and age metrics. |
| SQL Server | `(status, created_at) INCLUDE (partition_no)` | Read creation times in order and check partition membership from the index. |
| MySQL, MariaDB, H2     | `(partition_no, status, created_at)`          | Keep the creation time alongside the requested partition/status ranges.                       |
| Oracle                 | No additional age index                       | The tested variants did not justify another index for the unchanged timezone-aware aggregate. |

The MySQL optimizer did not consistently use the new index for a list containing all
256 partitions. The measured improvement applies to selected partition ranges, not
every parameter set. Oracle timestamp-specific query changes remain separate work.

## Index coverage and write cost

Fresh installations have the following secondary indexes on `outbox_record`, excluding the primary key:

| Database | Indexes | Net additional indexes compared with main |
|----------|--------:|-----------------------------------------:|
| PostgreSQL | 10 | 5 |
| MySQL | 8 | 3 |
| MariaDB | 7 | 2 |
| SQL Server | 8 | 3 |
| Oracle | 6 | 1 |
| H2 | 7 | 2 |

The indexes cover key selection, ordered record loading, retry and age queries, counts and retention reads.
Partial/filtered indexes cover incomplete records; full indexes also serve reads across all statuses.
MySQL's narrow partition/key index supports distinct-key enumeration, while the wider incomplete-record
index supports filtering by status and retry time. Those column orders serve different query shapes.

Some existing key-leading indexes overlap the new access paths. They remain in the tested baseline;
this does not establish that every index benefits every workload. Each index also adds storage and
maintenance work on inserts, updates and deletes. Further consolidation needs a comparison of the
affected read plans and write throughput, including workloads with substantial completed history.
Existing installations may have additional legacy indexes until they apply the optional cleanup below.

## Schema compatibility

Table and column definitions, timestamp precision and write SQL remain unchanged.
The schema files define the default layout for new installations using each database's original
index-creation style. There are no separate index-upgrade blocks or prepared upgrade statements.
Repeated initialization preserves existing records and indexes.

For existing databases, use the [index upgrade guide](INDEX_UPGRADE.md) to add the new indexes
and optionally remove overlapping old ones through your normal database deployment process.
MySQL, MariaDB, SQL Server and Oracle create these indexes only with a new table. PostgreSQL
and H2 retain their original standalone `CREATE INDEX IF NOT EXISTS` statements, which can add
missing indexes when executed against existing tables; they do not replace existing definitions.

Fresh installations omit these overlapping indexes:

| Database                                   | Index omitted from the fresh schema | Remaining access path                                                            |
|--------------------------------------------|-------------------------------------|----------------------------------------------------------------------------------|
| PostgreSQL, MySQL, MariaDB, SQL Server, H2 | `idx_outbox_record_status`          | Existing status-leading compound indexes.                                        |
| PostgreSQL, MySQL, MariaDB, SQL Server, H2 | `idx_outbox_instance_status`        | `idx_outbox_instance_status_heartbeat`.                                          |
| Oracle                                     | `idx_outbox_inst_stat`              | `idx_outbox_inst_s_h`.                                                           |
| Oracle                                     | `idx_outbox_rec_k_c_c`              | `idx_outbox_rec_key_comp_order`, which extends the same column prefix with `id`. |

For existing installations, the same names identify optional manual cleanup
candidates after checking application-specific queries and custom index definitions.
Automatic initialization preserves them. Resolve custom table/index names before
applying any manual cleanup.

The status/creation-time indexes from main remain in every schema. The ordinary key/creation
and partition/status/retry indexes remain because their leading columns and ordering serve
different reads.

MySQL adds `(completed_at, partition_no, record_key)` for distinct candidate keys. On the
large-backlog fixture its plan uses a covering skip scan for deduplication, followed by
global head lookup and a primary-key metadata join. No optimizer hints are required. The other candidate indexes
tested in the comparison were not retained without a demonstrated benefit.

The SQL Server incomplete ordered index includes partition, status and retry metadata
so that strict head selection does not need table lookups for those columns. Index
creation on large existing tables should use the database's normal deployment procedure.

See [the performance setup](../namastack-outbox-performance-test/README.md) for
reproducible read and end-to-end measurements without completion or deletion batching.
