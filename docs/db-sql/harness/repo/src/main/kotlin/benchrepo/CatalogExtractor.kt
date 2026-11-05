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
        "recordKeysWithPreviousFailureFilter" to listOf("findStrictRecordKeys"),
        "recordKeysWithoutPreviousFailureFilter" to listOf("findNonStrictRecordKeys"),
        "oldestDueNextRetryAtWithPreviousFailureFilter" to listOf("oldestDueStrict", "findOldestStrictDueRetry"),
        "oldestDueNextRetryAtWithoutPreviousFailureFilter" to listOf("oldestDueNonStrict", "findOldestDueRetry"),
        "findByKeysAndStatus" to listOf("findIncompleteByKeysAndStatus", "findByKeysAndStatus"),
        "findByKeyAndStatus" to listOf("findIncompleteByKeyAndStatus", "findByKeyAndStatus"),
    )

    /**
     * Reads one statement of the `query` package: a function when the SQL depends on the requested statuses
     * (called with what the entry measures), otherwise a getter. A missing statement is an error, because a
     * skipped entry would silently leave the query out of the measurements.
     */
    private fun queryPackageStatement(cl: ClassLoader, inst: Any, e: Entry): Pair<String, String>? {
        val base = e.newField ?: return null
        val names = QUERY_PACKAGE_NAMES[base] ?: listOf(base)
        @Suppress("UNCHECKED_CAST")
        val statusCls = cl.loadClass("io.namastack.outbox.OutboxRecordStatus") as Class<out Enum<*>>
        val statuses = statusCls.enumConstants.associateBy { it.name }
        val fnArg: Any? = when (e.id) {
            "count_statuses" -> setOf(statuses.getValue("NEW"), statuses.getValue("FAILED"))
            "count_statuses_all" -> statuses.values.toSet()
            "count_partitions" -> statuses.getValue("NEW")
            else -> null
        }
        if (fnArg != null) {
            inst.javaClass.methods.firstOrNull { it.name == base && it.parameterCount == 1 }?.let { m ->
                return (m.invoke(inst, fnArg) as String) to "${inst.javaClass.simpleName}.$base"
            }
        }
        for (name in names) {
            inst.javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }?.let { getter ->
                return (getter.invoke(inst) as String) to "${inst.javaClass.simpleName}.$name"
            }
        }
        error("no statement for ${e.id} on ${inst.javaClass.name}; tried $names")
    }

    /** Reads a private query field of the repository, plain or `by lazy`. */
    private fun repositoryField(repo: Any, name: String): String {
        val cls = repo.javaClass
        cls.declaredFields.firstOrNull { it.name == name }?.let { f ->
            f.isAccessible = true
            return f.get(repo) as String
        }
        val delegate = cls.getDeclaredField("$name\$delegate").also { it.isAccessible = true }
        return (delegate.get(repo) as Lazy<*>).value as String
    }

    fun run(o: Opts) {
        val variant = o.get("variant"); val table = o.opt("table") ?: "outbox_record"
        val outRoot = File(o.opt("catalog") ?: "catalog", variant)
        val cl = Thread.currentThread().contextClassLoader
        val factoryClass = try { cl.loadClass("io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory") } catch (_: ClassNotFoundException) { null }
        val queriesClass = if (factoryClass != null) null else try { cl.loadClass("io.namastack.outbox.JdbcOutboxRecordQueries") } catch (_: ClassNotFoundException) { null }
        for (d in Dialect.entries) {
            val dir = File(outRoot, d.id).also { it.deleteRecursively(); it.mkdirs() }
            val (source: (Entry) -> Pair<String, String>?, mode) =
                if (factoryClass != null) {
                    val factory = factoryClass.getField("INSTANCE").get(null)
                    val create = factoryClass.methods.first { it.name == "create" }
                    val typeCls = cl.loadClass("io.namastack.outbox.config.JdbcDatabaseType\$${V1_TYPES.getValue(d)}")
                    val inst = create.invoke(factory, typeCls.getField("INSTANCE").get(null), table)
                    val fn = { e: Entry -> queryPackageStatement(cl, inst, e) }
                    Pair(fn, "query-package")
                } else if (queriesClass != null) {
                    val companion = queriesClass.getField("Companion").get(null)
                    val of = companion.javaClass.methods.first { it.name == "of" }
                    val typeCls = cl.loadClass("io.namastack.outbox.config.JdbcDatabaseType\$${V1_TYPES.getValue(d)}")
                    val inst = of.invoke(companion, typeCls.getField("INSTANCE").get(null), table)
                    val fn = { e: Entry ->
                        e.newField?.let { name ->
                            val getter = inst.javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }
                            getter?.let { (it.invoke(inst) as String) to "${inst.javaClass.simpleName}.$name" }
                        }
                    }
                    Pair(fn, "queries-class")
                } else {
                    val repo = oldRepository(cl, table, V1_TYPES.getValue(d))
                    val fn = { e: Entry ->
                        e.oldField?.let { name -> repositoryField(repo, name) to "JdbcOutboxRecordRepository.$name" }
                    }
                    Pair(fn, "repository-fields")
                }
            for (e in ENTRIES) {
                val s = source(e) ?: continue
                // Repository-field variants whose key query binds :limit in SQL (GH-551) do not call setMaxRows.
                val maxrows = when {
                    queriesClass != null || factoryClass != null -> e.maxNew
                    e.maxOld == "batchSize" && s.first.contains(":limit") -> "none"
                    else -> e.maxOld
                }
                val statusNote = if (e.id == "find_failed_all") "-- note: status FAILED bound by the harness\n" else ""
                File(dir, "${e.id}.sql").writeText(
                    "-- id: ${e.id}\n-- group: ${e.group}\n-- kind: ${e.kind}\n-- maxrows: $maxrows\n-- source: ${s.second} ($mode, $variant)\n$statusNote${s.first}\n")
            }
            println("catalog $variant/${d.id}: ${dir.listFiles()!!.size} queries ($mode)")
        }
    }

    private fun oldRepository(cl: ClassLoader, table: String, typeName: String): Any {
        val repoCls = cl.loadClass("io.namastack.outbox.JdbcOutboxRecordRepository")
        val mapperCls = cl.loadClass("io.namastack.outbox.JdbcOutboxRecordEntityMapper")
        val resolverCls = cl.loadClass("io.namastack.outbox.DefaultJdbcTableNameResolver")
        val ds = DriverManagerDataSource()
        val mapper = mapperCls.getConstructors().first().newInstance(BenchSerializer())
        val resolver = resolverCls.getConstructors().first { it.parameterCount == 0 }.newInstance()
        val ctor = repoCls.constructors.first()
        val args = mutableListOf<Any>(JdbcClient.create(ds), TransactionTemplate(DataSourceTransactionManager(ds)), mapper, Clock.systemUTC(), resolver)
        // GH-551 added a database type supplier, which renders the row-limit clause per dialect.
        if (ctor.parameterCount == 6) {
            val type = cl.loadClass("io.namastack.outbox.config.JdbcDatabaseType\$$typeName").getField("INSTANCE").get(null)
            args += { type }
        }
        return ctor.newInstance(*args.toTypedArray())
    }
}
