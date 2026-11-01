package benchrepo

import bench.Dialect
import bench.Opts
import io.namastack.outbox.bench.BenchSerializer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.time.Clock

/**
 * Extracts the SQL the JDBC repository really sends from the worktree's jars (by reflection):
 * - variants with `JdbcOutboxRecordQueries` read it through `JdbcOutboxRecordQueries.of(type, table)`;
 * - variants with the `query` package read it through `JdbcOutboxRecordQueriesFactory.create(type, table)`
 *   (statement names renamed, see [QUERY_PACKAGE_NAMES]);
 * - older variants read the private query fields of `JdbcOutboxRecordRepository`.
 */
object CatalogExtractor {
    class Entry(
        val id: String, val group: String, val kind: String,
        val oldField: String?, val newField: String?, val maxOld: String, val maxNew: String,
    )

    private val ENTRIES = listOf(
        Entry("keys_strict", "HOT", "read", "recordKeysQueryWithPreviousFailureFilterTemplate", "recordKeysWithPreviousFailureFilter", "batchSize", "batchSize"),
        Entry("keys_nonstrict", "HOT", "read", "recordKeysQueryWithoutPreviousFailureFilterTemplate", "recordKeysWithoutPreviousFailureFilter", "batchSize", "batchSize"),
        Entry("pages", "HOT", "read", "findByKeysAndStatusQuery", "findByKeysAndStatus", "none", "none"),
        Entry("insert", "WRITE", "write", "insertRecordQuery", "insertRecord", "none", "none"),
        Entry("upd_complete", "WRITE", "write", "updateProcessingStateQuery", "updateProcessingState", "none", "none"),
        Entry("upd_retry", "WRITE", "write", "updateProcessingStateQuery", "updateProcessingState", "none", "none"),
        Entry("upd_fail", "WRITE", "write", "updateProcessingStateQuery", "updateProcessingState", "none", "none"),
        Entry("delete_by_id", "WRITE", "delete", "deleteByIdQuery", "deleteById", "none", "none"),
        Entry("count_new", "MON", "read", "countByStatusQuery", "countByStatus", "none", "none"),
        Entry("count_failed", "MON", "read", "countByStatusQuery", "countByStatus", "none", "none"),
        Entry("count_completed", "MON", "read", "countByStatusQuery", "countByStatus", "none", "none"),
        Entry("count_statuses", "MON", "read", null, "countByStatuses", "none", "none"),
        Entry("count_statuses_all", "MON", "read", null, "countByStatuses", "none", "none"),
        Entry("count_partitions", "MON", "read", "countByPartitionsStatusQuery", "countByPartitionsStatus", "none", "none"),
        Entry("count_partition_single", "MON", "read", "countByPartitionStatusQuery", "countByPartitionStatus", "none", "none"),
        Entry("oldest_created", "MON", "read", "oldestCreatedAtByPartitionsStatusQuery", "oldestCreatedAtByPartitionsStatus", "none", "one"),
        Entry("oldest_due_strict", "MON", "read", "oldestDueNextRetryAtQueryWithPreviousFailureFilter", "oldestDueNextRetryAtWithPreviousFailureFilter", "none", "one"),
        Entry("oldest_due_nonstrict", "MON", "read", "oldestDueNextRetryAtQueryWithoutPreviousFailureFilter", "oldestDueNextRetryAtWithoutPreviousFailureFilter", "none", "one"),
        Entry("find_completed_before", "RET", "read", "findByStatusAndCompletedBeforeQuery", "findByStatusAndCompletedBefore", "retention", "retention"),
        Entry("find_failed_before", "RET", "read", "findByStatusAndNextRetryBeforeQuery", "findByStatusAndNextRetryBefore", "retention", "retention"),
        Entry("ids_completed_before", "RET", "read", "findIdsByStatusAndCompletedBeforeQuery", "findIdsByStatusAndCompletedBefore", "retention", "retention"),
        Entry("ids_failed_before", "RET", "read", "findIdsByStatusAndNextRetryBeforeQuery", "findIdsByStatusAndNextRetryBefore", "retention", "retention"),
        Entry("delete_by_ids", "RET", "delete", "deleteByIdsQuery", "deleteByIds", "none", "none"),
        Entry("delete_by_status_failed", "RET", "delete", "deleteByStatusQuery", "deleteByStatus", "none", "none"),
        Entry("delete_by_key_status", "RET", "delete", "deleteByKeyAndStatusQuery", "deleteByKeyAndStatus", "none", "none"),
        Entry("find_pending_all", "ADMIN", "read", "findByStatusQuery", "findByStatus", "none", "none"),
        Entry("find_failed_all", "ADMIN", "read", "findByStatusQuery", "findByStatus", "none", "none"),
        Entry("find_incomplete_by_key", "ADMIN", "read", "findByKeyAndStatusQuery", "findByKeyAndStatus", "none", "none"),
    )

    private val V1_TYPES = mapOf(
        Dialect.POSTGRES to "PostgreSQL", Dialect.MYSQL to "MySQL", Dialect.MARIADB to "MariaDB",
        Dialect.SQLSERVER to "SQLServer", Dialect.ORACLE to "Oracle", Dialect.H2 to "H2",
    )

    /** Statement names of the `query` package that differ from the `JdbcOutboxRecordQueries.of` variant. */
    private val QUERY_PACKAGE_NAMES = mapOf(
        "recordKeysWithPreviousFailureFilter" to "findStrictRecordKeys",
        "recordKeysWithoutPreviousFailureFilter" to "findNonStrictRecordKeys",
        "oldestDueNextRetryAtWithPreviousFailureFilter" to "findOldestStrictDueRetry",
        "oldestDueNextRetryAtWithoutPreviousFailureFilter" to "findOldestDueRetry",
    )

    fun run(o: Opts) {
        val variant = o.get("variant"); val table = o.opt("table") ?: "outbox_record"
        val outRoot = File(o.opt("catalog") ?: "catalog", variant)
        val cl = Thread.currentThread().contextClassLoader
        val factoryClass = try { cl.loadClass("io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory") } catch (_: ClassNotFoundException) { null }
        val queriesClass = if (factoryClass != null) null else try { cl.loadClass("io.namastack.outbox.JdbcOutboxRecordQueries") } catch (_: ClassNotFoundException) { null }
        for (d in Dialect.entries) {
            val dir = File(outRoot, d.id).also { it.deleteRecursively(); it.mkdirs() }
            val (source: (String?, String?) -> Pair<String, String>?, mode) =
                if (factoryClass != null) {
                    val factory = factoryClass.getField("INSTANCE").get(null)
                    val create = factoryClass.methods.first { it.name == "create" }
                    val typeCls = cl.loadClass("io.namastack.outbox.config.JdbcDatabaseType\$${V1_TYPES.getValue(d)}")
                    val inst = create.invoke(factory, typeCls.getField("INSTANCE").get(null), table)
                    val fn = { _: String?, n: String? ->
                        n?.let { old ->
                            val name = QUERY_PACKAGE_NAMES[old] ?: old
                            val getter = inst.javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }
                            getter?.let { (it.invoke(inst) as String) to "${inst.javaClass.simpleName}.$name" }
                        }
                    }
                    Pair(fn, "query-package")
                } else if (queriesClass != null) {
                    val companion = queriesClass.getField("Companion").get(null)
                    val of = companion.javaClass.methods.first { it.name == "of" }
                    val typeCls = cl.loadClass("io.namastack.outbox.config.JdbcDatabaseType\$${V1_TYPES.getValue(d)}")
                    val inst = of.invoke(companion, typeCls.getField("INSTANCE").get(null), table)
                    val fn = { _: String?, n: String? ->
                        n?.let { name ->
                            val getter = inst.javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }
                            getter?.let { (it.invoke(inst) as String) to "${inst.javaClass.simpleName}.$name" }
                        }
                    }
                    Pair(fn, "queries-class")
                } else {
                    val repo = oldRepository(cl, table)
                    val fn = { f: String?, _: String? ->
                        f?.let { name -> repo.javaClass.getDeclaredField(name).also { it.isAccessible = true }.let { (it.get(repo) as String) to "JdbcOutboxRecordRepository.$name" } }
                    }
                    Pair(fn, "repository-fields")
                }
            for (e in ENTRIES) {
                val s = source(e.oldField, e.newField) ?: continue
                val maxrows = if (queriesClass != null || factoryClass != null) e.maxNew else e.maxOld
                val statusNote = if (e.id == "find_failed_all") "-- note: status FAILED bound by the harness\n" else ""
                File(dir, "${e.id}.sql").writeText(
                    "-- id: ${e.id}\n-- group: ${e.group}\n-- kind: ${e.kind}\n-- maxrows: $maxrows\n-- source: ${s.second} ($mode, $variant)\n$statusNote${s.first}\n")
            }
            println("catalog $variant/${d.id}: ${dir.listFiles()!!.size} queries ($mode)")
        }
    }

    private fun oldRepository(cl: ClassLoader, table: String): Any {
        val repoCls = cl.loadClass("io.namastack.outbox.JdbcOutboxRecordRepository")
        val mapperCls = cl.loadClass("io.namastack.outbox.JdbcOutboxRecordEntityMapper")
        val resolverCls = cl.loadClass("io.namastack.outbox.DefaultJdbcTableNameResolver")
        val ds = DriverManagerDataSource()
        val mapper = mapperCls.getConstructors().first().newInstance(BenchSerializer())
        val resolver = resolverCls.getConstructors().first { it.parameterCount == 0 }.newInstance()
        val ctor = repoCls.constructors.first()
        return ctor.newInstance(JdbcClient.create(ds), TransactionTemplate(DataSourceTransactionManager(ds)), mapper, Clock.systemUTC(), resolver)
    }
}
