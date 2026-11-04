# Upgrading JDBC indexes

New installations get the optimized indexes from the [schema files](src/main/resources/schema).
For an existing installation, add the indexes below through your normal database deployment
process. There are no table, column, timestamp-precision or record-format changes.
The new queries also work with the old indexes; adding these indexes enables the read optimizations.

## Before applying the changes

- Check the existing index definitions and skip indexes that already have the same definition,
  including equivalent indexes under custom names. The examples below are one-time SQL statements.
- An existing index with the same name but different columns must be rebuilt through your database
  deployment process. For example, PostgreSQL's `idx_outbox_record_status_created` now includes `id`
  in its ordering and covers the polling metadata; `IF NOT EXISTS` does not update an older definition.
- Use your actual schema, table and index names if you configured custom naming.
- Create the new indexes before optionally removing overlapping old indexes. On large tables,
  use your database's supported online/concurrent index-building procedure as appropriate.

The bootstrap scripts follow each database's original creation style. MySQL, MariaDB and
SQL Server define indexes in `CREATE TABLE`; Oracle creates them inside the table-creation block.
Re-running those scripts does not upgrade an existing table's indexes. PostgreSQL and H2 retain
standalone `CREATE INDEX IF NOT EXISTS` statements, so running their bootstrap scripts may add
missing indexes. None of the scripts replaces an existing index or removes an old one.
Do not rely on bootstrap execution as a production upgrade mechanism.

## Add the new indexes

### PostgreSQL

```sql
CREATE INDEX idx_outbox_record_key_incomplete_ordered
    ON outbox_record (record_key, created_at, id)
    INCLUDE (partition_no, status, next_retry_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_status_retry_incomplete
    ON outbox_record (status, next_retry_at)
    INCLUDE (partition_no, created_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_status_partition
    ON outbox_record (status, partition_no);

CREATE INDEX idx_outbox_record_key_status
    ON outbox_record (record_key, status);

CREATE INDEX idx_outbox_record_completed_retention
    ON outbox_record (completed_at)
    WHERE completed_at IS NOT NULL;

CREATE INDEX idx_outbox_record_status_created
    ON outbox_record (status, created_at, id)
    INCLUDE (record_key, partition_no, next_retry_at, completed_at);
```

### MySQL

```sql
CREATE INDEX idx_outbox_record_completed_partition
    ON outbox_record (completed_at, partition_no, status, next_retry_at, record_key, created_at);

CREATE INDEX idx_outbox_record_partition_created
    ON outbox_record (partition_no, status, created_at);

CREATE INDEX idx_outbox_record_partition_keys
    ON outbox_record (completed_at, partition_no, record_key);
```

### MariaDB

```sql
CREATE INDEX idx_outbox_record_completed_partition
    ON outbox_record (completed_at, partition_no, status, next_retry_at, record_key, created_at);

CREATE INDEX idx_outbox_record_partition_created
    ON outbox_record (partition_no, status, created_at);
```

### SQL Server

```sql
CREATE INDEX idx_outbox_record_incomplete_partition
    ON outbox_record (partition_no, status, next_retry_at)
    INCLUDE (record_key, created_at, completed_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_key_incomplete_ordered
    ON outbox_record (record_key, created_at, id)
    INCLUDE (status, completed_at, partition_no, next_retry_at)
    WHERE completed_at IS NULL;

CREATE INDEX idx_outbox_record_completed_at
    ON outbox_record (completed_at)
    INCLUDE (id, status)
    WHERE completed_at IS NOT NULL;

CREATE INDEX idx_outbox_record_status_created
    ON outbox_record (status, created_at)
    INCLUDE (partition_no);
```

### Oracle

```sql
CREATE INDEX idx_outbox_rec_completed_part ON outbox_record
    (completed_at, partition_no, status, next_retry_at, record_key, created_at);

CREATE INDEX idx_outbox_rec_key_comp_order ON outbox_record
    (record_key, completed_at, created_at, id);
```

### H2

```sql
CREATE INDEX idx_outbox_record_completed_key_ordered
    ON outbox_record (completed_at, record_key, created_at, id);

CREATE INDEX idx_outbox_record_partition_created
    ON outbox_record (partition_no, status, created_at);
```

## Optional cleanup of overlapping old indexes

The new fresh-installation schemas omit the indexes below. Existing installations can keep them;
removing them is optional and reduces index maintenance on inserts, updates and deletes.
Check application-specific queries and custom definitions before removing an index.

| Database                                   | Table             | Old index                    | Remaining access path                                                         |
|--------------------------------------------|-------------------|------------------------------|-------------------------------------------------------------------------------|
| PostgreSQL, MySQL, MariaDB, SQL Server, H2 | `outbox_record`   | `idx_outbox_record_status`   | Existing status-leading compound indexes.                                     |
| PostgreSQL, MySQL, MariaDB, SQL Server, H2 | `outbox_instance` | `idx_outbox_instance_status` | `idx_outbox_instance_status_heartbeat`.                                       |
| Oracle                                     | `outbox_instance` | `idx_outbox_inst_stat`       | `idx_outbox_inst_s_h`.                                                        |
| Oracle                                     | `outbox_record`   | `idx_outbox_rec_k_c_c`       | New `idx_outbox_rec_key_comp_order`, with the same leading columns plus `id`. |

For PostgreSQL, H2 and Oracle, use `DROP INDEX index_name`. For MySQL, MariaDB and SQL Server,
use `DROP INDEX index_name ON table_name`. Apply only the entries for your database that exist.
The status/creation-time indexes from main remain in every default schema; keep them when applying these additions.

## After upgrading

Confirm that the indexes match the relevant [fresh schema](src/main/resources/schema), allowing
for any old indexes you chose to retain. Normal database statistics maintenance remains unchanged.
No extended statistics or automatic library migrations are required by this change.
