package bench

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Connection details and per-dialect SQL helpers. All hosts are 127.0.0.1 (see bin/db.sh for ports). */
enum class Dialect(val id: String, val container: String?) {
    POSTGRES("postgres", "outbox-bench-postgres"),
    MYSQL("mysql", "outbox-bench-mysql"),
    MARIADB("mariadb", "outbox-bench-mariadb"),
    SQLSERVER("sqlserver", "outbox-bench-sqlserver"),
    ORACLE("oracle", "outbox-bench-oracle"),
    H2("h2", null);

    companion object {
        fun of(s: String) = entries.firstOrNull { it.id == s.lowercase() } ?: error("unknown dialect $s")
        /** PostgreSQL statements run on every new connection (`--pg-plan-cache-mode`). */
        var pgSessionSql: List<String> = emptyList()
        val h2Dir: File = File(System.getProperty("harness.h2dir") ?: "build/h2")
    }

    /** Connection scoped to a schema (PG schema, MySQL/MariaDB/SQL Server database, Oracle user, H2 schema). */
    fun connect(schema: String): Connection {
        val c = when (this) {
            POSTGRES -> DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:15432/bench?currentSchema=$schema&ApplicationName=harness", "bench", "bench")
            MYSQL -> DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:13306/$schema?rewriteBatchedStatements=true&sessionVariables=time_zone='%2B00:00'" +
                    "&allowPublicKeyRetrieval=true&useSSL=false", "root", "bench")
            MARIADB -> DriverManager.getConnection(
                "jdbc:mariadb://127.0.0.1:13307/$schema?sessionVariables=time_zone='+00:00'", "root", "bench")
            SQLSERVER -> DriverManager.getConnection(
                "jdbc:sqlserver://127.0.0.1:11433;databaseName=$schema;encrypt=false;trustServerCertificate=true", "sa", "Bench_pw1234")
            ORACLE -> DriverManager.getConnection("jdbc:oracle:thin:@//127.0.0.1:11521/XEPDB1", schema, "bench")
            H2 -> {
                h2Dir.mkdirs()
                DriverManager.getConnection(
                    "jdbc:h2:file:${h2Dir.absolutePath}/bench;DB_CLOSE_ON_EXIT=FALSE;INIT=CREATE SCHEMA IF NOT EXISTS ${schema.uppercase()}\\;SET SCHEMA ${schema.uppercase()}", "sa", "")
            }
        }
        if (this == ORACLE) c.createStatement().use { it.execute("ALTER SESSION SET TIME_ZONE = '+00:00'") }
        if (this == POSTGRES) for (sql in pgSessionSql) c.createStatement().use { it.execute(sql) }
        return c
    }

    /** Admin connection used to create/drop schemas. */
    fun admin(): Connection = when (this) {
        POSTGRES -> connect("public")
        MYSQL -> connect("mysql")
        MARIADB -> connect("mysql")
        SQLSERVER -> connect("master")
        ORACLE -> DriverManager.getConnection("jdbc:oracle:thin:@//127.0.0.1:11521/XEPDB1", "system", "bench")
        H2 -> connect("PUBLIC")
    }

    fun resetSchema(name: String) {
        admin().use { c ->
            c.autoCommit = true
            val st = c.createStatement()
            when (this) {
                POSTGRES -> { st.execute("DROP SCHEMA IF EXISTS $name CASCADE"); st.execute("CREATE SCHEMA $name") }
                MYSQL, MARIADB -> { st.execute("DROP DATABASE IF EXISTS $name"); st.execute("CREATE DATABASE $name") }
                SQLSERVER -> {
                    st.execute("IF DB_ID('$name') IS NOT NULL BEGIN ALTER DATABASE $name SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE $name; END")
                    st.execute("CREATE DATABASE $name")
                }
                ORACLE -> {
                    val rs = st.executeQuery("SELECT COUNT(*) FROM dba_users WHERE username = '${name.uppercase()}'")
                    rs.next(); if (rs.getInt(1) > 0) st.execute("DROP USER $name CASCADE")
                    st.execute("CREATE USER $name IDENTIFIED BY bench QUOTA UNLIMITED ON USERS")
                    st.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE SEQUENCE TO $name")
                }
                H2 -> { st.execute("DROP SCHEMA IF EXISTS ${name.uppercase()} CASCADE"); st.execute("CREATE SCHEMA ${name.uppercase()}") }
            }
        }
    }

    fun schemaExists(name: String): Boolean = try {
        admin().use { c ->
            val q = when (this) {
                POSTGRES -> "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$name'"
                MYSQL, MARIADB -> "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$name'"
                SQLSERVER -> "SELECT COUNT(*) FROM sys.databases WHERE name='$name'"
                ORACLE -> "SELECT COUNT(*) FROM dba_users WHERE username='${name.uppercase()}'"
                H2 -> "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${name.uppercase()}'"
            }
            val rs = c.createStatement().executeQuery(q); rs.next(); rs.getInt(1) > 0
        }
    } catch (e: Exception) { false }

    /** Qualified name of the snapshot table as seen from a run schema. */
    val snapTable: String get() = when (this) {
        POSTGRES, MYSQL, MARIADB, ORACLE -> "snap.rec"
        SQLSERVER -> "snap.dbo.rec"
        H2 -> "SNAP.REC"
    }

    /** DDL of the (unindexed, key-less) snapshot table. */
    fun snapDdl(): String {
        val (str, text, ts, int) = when (this) {
            POSTGRES -> listOf("varchar", "text", "timestamptz", "int")
            MYSQL, MARIADB -> listOf("varchar", "longtext", "timestamp NULL", "int")
            SQLSERVER -> listOf("varchar", "nvarchar(max)", "datetime2(6)", "int")
            ORACLE -> listOf("varchar2", "nclob", "timestamp with time zone", "number(10)")
            H2 -> listOf("varchar", "clob", "timestamp with time zone", "int")
        }
        val n = if (this == MYSQL || this == MARIADB) " NULL" else ""
        return """CREATE ${if (this == POSTGRES) "UNLOGGED " else ""}TABLE rec (id $str(255), status $str(20), record_key $str(255), record_type $str(255), payload $text,
            context $text, created_at $ts, completed_at $ts, failure_count $int, failure_reason $str(1000),
            next_retry_at ${ts.replace(" NULL", "")}$n, partition_no $int, handler_id $str(1000))""".trimIndent().replace("\n", " ")
    }

    val analyzeSql: List<String> get() = when (this) {
        POSTGRES -> listOf("VACUUM (FREEZE, ANALYZE) outbox_record")
        MYSQL, MARIADB -> listOf("ANALYZE TABLE outbox_record")
        SQLSERVER -> listOf("UPDATE STATISTICS outbox_record WITH FULLSCAN")
        ORACLE -> listOf("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'OUTBOX_RECORD', cascade => TRUE); END;")
        H2 -> listOf("ANALYZE")
    }

    /** Split a dialect DDL file into statements (Oracle: PL/SQL blocks terminated by a '/' line). */
    fun splitDdl(text: String): List<String> {
        val noComments = text.lines().filter { !it.trim().startsWith("--") }
        if (this == ORACLE) {
            val out = mutableListOf<String>(); val cur = StringBuilder()
            for (l in noComments) {
                if (l.trim() == "/") { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() } else cur.appendLine(l)
            }
            if (cur.isNotBlank()) out += cur.toString().trim()
            return out
        }
        return noComments.joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Size report: rows by status, table and index sizes, index count. */
    fun sizeReport(c: Connection): String {
        val sb = StringBuilder()
        fun q(sql: String, label: String) = try {
            c.createStatement().use { st -> st.executeQuery(sql).use { rs ->
                val md = rs.metaData
                sb.appendLine("# $label")
                while (rs.next()) sb.appendLine((1..md.columnCount).joinToString("\t") { rs.getString(it) ?: "" })
            } }
        } catch (e: Exception) { sb.appendLine("# $label: failed ${e.message?.lines()?.first()}") }
        q("SELECT status, COUNT(*) FROM outbox_record GROUP BY status ORDER BY status", "rows_by_status")
        when (this) {
            POSTGRES -> {
                q("SELECT pg_size_pretty(pg_table_size('outbox_record')) table_size, pg_table_size('outbox_record') bytes", "table_size (heap+toast)")
                q("SELECT pg_size_pretty(pg_indexes_size('outbox_record')) indexes_size, pg_indexes_size('outbox_record') bytes", "indexes_size_total")
                q("SELECT indexrelid::regclass::text, pg_relation_size(indexrelid) bytes, pg_size_pretty(pg_relation_size(indexrelid)) FROM pg_index WHERE indrelid='outbox_record'::regclass ORDER BY 1", "index_sizes (incl. pkey)")
                q("SELECT COUNT(*) FROM pg_index WHERE indrelid='outbox_record'::regclass", "index_count (incl. pkey)")
                q("SELECT n_live_tup, n_dead_tup, last_vacuum, last_autovacuum, last_autoanalyze, vacuum_count, autovacuum_count FROM pg_stat_user_tables WHERE relname='outbox_record' AND schemaname=current_schema()", "pg_stat_user_tables")
            }
            MYSQL, MARIADB -> {
                q("SELECT table_rows, data_length, index_length FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='outbox_record'", "table_rows,data_length,index_length")
                q("SELECT index_name, stat_value*@@innodb_page_size bytes FROM mysql.innodb_index_stats WHERE database_name=DATABASE() AND table_name='outbox_record' AND stat_name='size' ORDER BY 1", "index_sizes")
                q("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='outbox_record'", "index_count (incl. primary)")
            }
            SQLSERVER -> {
                q("SELECT i.name, SUM(s.used_page_count)*8*1024 bytes FROM sys.dm_db_partition_stats s JOIN sys.indexes i ON i.object_id=s.object_id AND i.index_id=s.index_id WHERE s.object_id=OBJECT_ID('outbox_record') GROUP BY i.name ORDER BY 1", "index_sizes (clustered = table)")
                q("SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID('outbox_record') AND type>0", "index_count")
            }
            ORACLE -> {
                q("SELECT segment_name, segment_type, bytes FROM user_segments WHERE segment_name='OUTBOX_RECORD' OR segment_name IN (SELECT index_name FROM user_indexes WHERE table_name='OUTBOX_RECORD') ORDER BY 1", "segments")
                q("SELECT COUNT(*) FROM user_indexes WHERE table_name='OUTBOX_RECORD'", "index_count")
            }
            H2 -> {
                q("SELECT index_name FROM information_schema.indexes WHERE table_name='OUTBOX_RECORD' AND table_schema=SCHEMA() GROUP BY index_name", "indexes")
            }
        }
        return sb.toString()
    }
}
