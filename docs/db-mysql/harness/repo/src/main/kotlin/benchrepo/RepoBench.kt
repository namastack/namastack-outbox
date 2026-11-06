package benchrepo

import bench.Bench
import bench.Common
import bench.Ctx
import bench.Dialect
import bench.Env
import bench.Opts
import bench.Realisation
import bench.T0_MICROS
import bench.DAY
import bench.Hasher
import bench.instantOfMicros
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.bench.BenchSerializer
import io.namastack.outbox.bench.OrderCreatedPayload
import jakarta.persistence.EntityManager
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import javax.sql.DataSource

/** Records every statement prepared through the data source (the SQL both JdbcClient and Hibernate really send). */
class SqlLog {
    class Entry(val sql: String) { var maxRows = 0; var count = 0 }
    @Volatile var current: MutableList<Entry>? = null
}

fun capturingDataSource(target: DataSource, log: SqlLog): DataSource =
    Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, m, a ->
        val r = invoke(m, target, a)
        if (m.name == "getConnection" && r is Connection) wrapConn(r, log) else r
    } as DataSource

private fun invoke(m: Method, target: Any, a: Array<Any?>?): Any? = try { m.invoke(target, *(a ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }

private fun wrapConn(c: Connection, log: SqlLog): Connection =
    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, m, a ->
        val r = invoke(m, c, a)
        if (m.name == "prepareStatement" && r is PreparedStatement) {
            val e = SqlLog.Entry(a!![0] as String).also { log.current?.add(it) }
            Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, m2, a2 ->
                if (m2.name == "setMaxRows") e.maxRows = a2!![0] as Int
                invoke(m2, r, a2)
            } as PreparedStatement
        } else r
    } as Connection

/** Reflection over the repository's lazily resolved dialect/query objects: class names prove which dialect class was selected. */
fun describeDialect(repo: Any): String {
    val sb = StringBuilder()
    var cls: Class<*>? = repo.javaClass
    while (cls != null && cls != Any::class.java) {
        for (f in cls.declaredFields) {
            try {
                f.isAccessible = true
                val v = f.get(repo)
                val target = if (v is Lazy<*>) v.value else v
                if (target is String && f.name.contains("rowLimit", true) && target.length < 80) sb.appendLine("# repository field ${f.name}: '$target'")
                else if (target != null && ("Queries" in target.javaClass.name || "DatabaseType" in target.javaClass.name || target.javaClass.simpleName.contains("Dialect")))
                    sb.appendLine("# repository field ${f.name}: ${target.javaClass.name}${if (target is Enum<*>) " = $target" else ""}")
            } catch (_: Exception) {}
        }
        cls = cls.superclass
    }
    return sb.toString().trimEnd().ifEmpty { "# repository dialect class: not found by reflection" }
}

/**
 * Calls a repository method whose signature differs between variants: `statusIdx` is the position of the `OutboxRecordStatus`
 * argument that some variants do not have (the runner is compiled against one variant's interface, runs against another's).
 */
fun callFlex(repo: Any, name: String, statusIdx: Int, vararg args: Any?): Any? {
    val ms = OutboxRecordRepository::class.java.methods.filter { it.name == name }
    val full = ms.firstOrNull { it.parameterCount == args.size }
    val short = ms.firstOrNull { it.parameterCount == args.size - 1 }
    return try {
        if (full != null) full.invoke(repo, *args)
        else short!!.invoke(repo, *args.toList().filterIndexed { i, _ -> i != statusIdx }.toTypedArray())
    } catch (e: InvocationTargetException) { throw e.targetException }
}

/** Repository-level ids of Priority 3 methods (find*Records, delete*Records, deleteByIds, deleteByStatus, deleteByRecordKeyAndStatus, findIncompleteRecordsByRecordKey): skipped. */
val P3_IDS = setOf("find_completed_before", "find_failed_before", "find_incomplete_by_key", "find_pending_all", "find_failed_all",
    "delete_completed_batch", "delete_failed_batch", "delete_by_status_failed", "delete_by_key_status", "delete_by_ids")

object RepoBench {
    class Wiring(val repo: OutboxRecordRepository, val tx: TransactionTemplate, val close: () -> Unit)

    fun wire(engine: String, ds: DataSource): Wiring {
        val cl = Thread.currentThread().contextClassLoader
        val serializer = BenchSerializer()
        return if (engine == "jdbc") {
            val tm = DataSourceTransactionManager(ds)
            val factory = cl.loadClass("io.namastack.outbox.JdbcOutboxPersistenceFactory").getField("INSTANCE").get(null)
            val create = factory.javaClass.methods.first { it.name == "create" && it.parameterCount == 5 }
            val persistence = create.invoke(factory, ds, tm, serializer, Clock.systemUTC(), cl.loadClass("io.namastack.outbox.DefaultJdbcTableNameResolver").getConstructor().newInstance())
            val recordRepo = persistence.javaClass.getMethod("getRecordRepository").invoke(persistence) as OutboxRecordRepository
            Wiring(recordRepo, TransactionTemplate(tm)) {}
        } else {
            val emfBean = LocalContainerEntityManagerFactoryBean()
            emfBean.dataSource = ds
            emfBean.setPackagesToScan("io.namastack.outbox")
            emfBean.jpaVendorAdapter = HibernateJpaVendorAdapter()
            emfBean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            emfBean.afterPropertiesSet()
            val emf = emfBean.`object`!!
            val tm = JpaTransactionManager(emf)
            val em: EntityManager = SharedEntityManagerCreator.createSharedEntityManager(emf)
            val tt = TransactionTemplate(tm)
            val mapperCls = cl.loadClass("io.namastack.outbox.OutboxRecordEntityMapper")
            val mapper = mapperCls.constructors.first().newInstance(serializer)
            val repoCls = cl.loadClass("io.namastack.outbox.JpaOutboxRecordRepository")
            val repo = repoCls.constructors.first().newInstance(em, tt, mapper, Clock.systemUTC()) as OutboxRecordRepository
            Wiring(repo, tt) { emf.close() }
        }
    }

    private fun record(id: String, key: String, status: OutboxRecordStatus, completedAt: java.time.Instant?, failureCount: Int,
                       reason: String?, next: java.time.Instant, created: java.time.Instant): OutboxRecord<*> =
        OutboxRecord.restore(id, key, OrderCreatedPayload("x".repeat(490)), emptyMap(), created, status, completedAt, failureCount,
            null, reason, Hasher.partition(key), next, Realisation.HANDLERS[0])

    fun run(cm: Common, o: Opts) {
        val engine = o.get("engine")
        val outDir = File(cm.outDir, "repo-$engine").also { it.mkdirs() }
        val log = SqlLog()
        require(cm.dialect == Dialect.POSTGRES || ((cm.dialect == Dialect.MARIADB || cm.dialect == Dialect.MYSQL) && engine == "jdbc")) { "repository-level runner supports postgres (jdbc, jpa), mariadb and mysql (jdbc only)" }
        val hikari = HikariDataSource(HikariConfig().apply {
            if (cm.dialect == Dialect.MYSQL) {   // MySQL Connector/J on the lane's port, driver defaults (client-side prepared statements, no rewriteBatchedStatements), UTC session
                jdbcUrl = "jdbc:mysql://127.0.0.1:${cm.dialect.port}/${cm.schema}?sessionVariables=time_zone='%2B00:00'&allowPublicKeyRetrieval=true&useSSL=false"
                username = "root"; password = "bench"
            } else if (cm.dialect == Dialect.MARIADB) {   // MariaDB Connector/J on the lane's port; same URL options as the SQL runner, no useMysqlMetadata
                jdbcUrl = "jdbc:mariadb://127.0.0.1:${cm.dialect.port}/${cm.schema}?sessionVariables=time_zone='+00:00'"
                username = "root"; password = "bench"
            } else {
                jdbcUrl = "jdbc:postgresql://127.0.0.1:15432/bench?currentSchema=${cm.schema}&ApplicationName=repo-bench"
                username = "bench"; password = "bench"
            }
            maximumPoolSize = 4; minimumIdle = 4
        })
        val w = wire(engine, capturingDataSource(hikari, log))
        val sqlOut = StringBuilder()
        hikari.connection.use { hc -> sqlOut.appendLine("# datasource: ${hc.metaData.databaseProductName} ${hc.metaData.databaseProductVersion.lines().first()} via ${hc.metaData.driverName} ${hc.metaData.driverVersion}") }
        val repo = w.repo; val tt = w.tx
        val owned = cm.owned.toSet(); val NEW = OutboxRecordStatus.NEW
        val t0 = instantOfMicros(T0_MICROS)
        val adminOnly = cm.scenario.uppercase() in setOf("S1", "S3")
        val only = o.opt("only")?.split(",")?.toSet()
        cm.dialect.connect(cm.schema).use { c ->
            val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, cm.warmup + cm.reps + 5)
            var lastKeys: Set<String> = emptySet()
            var insSeq = 0
            val statuses = try { repo.javaClass.methods.firstOrNull { it.name == "countByStatuses" } } catch (_: Exception) { null }
            class Op(val id: String, val rollback: Boolean, val fn: (Int) -> Int)
            fun size(x: Any?): Int = when (x) { is Collection<*> -> x.size; is Map<*, *> -> x.size; is Number -> 1; null -> 0; else -> 1 }
            val ops = mutableListOf<Op>()
            ops += Op("keys_strict", false) { lastKeys = (callFlex(repo, "findRecordKeysInPartitions", 1, owned, NEW, cm.batch, true) as List<String>).toSet(); lastKeys.size }
            ops += Op("keys_nonstrict", false) { size(callFlex(repo, "findRecordKeysInPartitions", 1, owned, NEW, cm.batch, false)) }
            ops += Op("pages", false) { size(repo.findIncompleteRecordPages(lastKeys.ifEmpty { ctx.dueKeys.take(cm.batch).toSet() })) }
            for (s in OutboxRecordStatus.entries) ops += Op("count_${s.name.lowercase()}", false) { repo.countByStatus(s); 1 }
            if (statuses != null) ops += Op("count_statuses", false) { size(statuses.invoke(repo, setOf(NEW, OutboxRecordStatus.FAILED))) }
            ops += Op("count_partitions", false) { repo.countRecordsByPartitions(owned, NEW); 1 }
            ops += Op("count_partition_single", false) { repo.countRecordsByPartition(ctx.samplePartition, NEW); 1 }
            ops += Op("oldest_created", false) { size(callFlex(repo, "findOldestCreatedAtByPartitions", 1, owned, NEW)) }
            ops += Op("oldest_due_strict", false) { size(callFlex(repo, "findOldestDueNextRetryAtByPartitions", 1, owned, NEW, t0, true)) }
            ops += Op("oldest_due_nonstrict", false) { size(callFlex(repo, "findOldestDueNextRetryAtByPartitions", 1, owned, NEW, t0, false)) }
            ops += Op("find_completed_before", false) { size(repo.findCompletedRecords(t0.minusSeconds(7 * 86400), 1000)) }
            ops += Op("find_failed_before", false) { size(repo.findFailedRecords(t0.minusSeconds(30 * 86400), 1000)) }
            ops += Op("find_incomplete_by_key", false) { size(repo.findIncompleteRecordsByRecordKey(ctx.sampleKey)) }
            if (adminOnly || o.has("all-admin")) {
                ops += Op("find_pending_all", false) { size(repo.findPendingRecords()) }
                ops += Op("find_failed_all", false) { size(repo.findFailedRecords()) }
            }
            ops += Op("insert", false) { i ->
                val p = hashMapOf<String, Any?>(); ctx.insertParams(p, 900_000 + insSeq++, T0_MICROS, "order-repo-")
                repo.create(record(p["id"] as String, p["recordKey"] as String, NEW, null, 0, null, t0, t0)); 1
            }
            for ((id, st) in listOf("upd_complete" to OutboxRecordStatus.COMPLETED, "upd_retry" to NEW, "upd_fail" to OutboxRecordStatus.FAILED)) {
                ops += Op(id, true) { i ->
                    val rid = ctx.dueIds[i % ctx.dueIds.size]
                    val r = record(rid, ctx.sampleKey, st, if (st == OutboxRecordStatus.COMPLETED) t0 else null, if (st == NEW) 1 else if (st == OutboxRecordStatus.FAILED) 4 else 0,
                        if (st == OutboxRecordStatus.COMPLETED) null else "boom", t0, t0)
                    if (repo.updateProcessingState(r)) 1 else 0
                }
            }
            ops += Op("delete_by_id", false) { i ->   // committed: a row is inserted by plain JDBC, then deleted through the repository
                val key = "order-repodel-" + i.toString().padStart(10, '0')
                val id = java.util.UUID.nameUUIDFromBytes(key.toByteArray()).toString()
                c.prepareStatement("INSERT INTO outbox_record (id,status,record_key,record_type,payload,created_at,failure_count,next_retry_at,partition_no,handler_id) VALUES (?,?,?,?,?,now(),0,now(),?,?)").use { ps ->
                    ps.setString(1, id); ps.setString(2, "COMPLETED"); ps.setString(3, key); ps.setString(4, Realisation.TYPES[0]); ps.setString(5, """{"d":"x"}"""); ps.setInt(6, Hasher.partition(key)); ps.setString(7, Realisation.HANDLERS[0]); ps.executeUpdate()
                }
                repo.deleteById(id); 1
            }
            ops += Op("delete_completed_batch", true) { repo.deleteCompletedRecords(t0.minusSeconds(7 * 86400), 1000) }
            ops += Op("delete_failed_batch", true) { repo.deleteFailedRecords(t0.minusSeconds(30 * 86400), 1000) }
            ops += Op("delete_by_status_failed", true) { repo.deleteByStatus(OutboxRecordStatus.FAILED); 0 }
            ops += Op("delete_by_key_status", true) { repo.deleteByRecordKeyAndStatus(ctx.sampleKey, NEW); 0 }

            val csv = StringBuilder("query_id,median_ms,p95_ms,min_ms,max_ms,rows,reps,note\n")
            for (op in ops) {
                if (op.id in P3_IDS) continue   // Priority 3 (RET, ADMIN) never runs in the MySQL study
                if (only != null && op.id !in only) continue
                try {
                    val times = mutableListOf<Double>(); var rows = 0; var note = ""
                    var wu = cm.warmup; var reps = cm.reps; var i = 0
                    while (i < wu + reps) {
                        val captured = mutableListOf<SqlLog.Entry>()
                        if (i == wu + reps - 1) log.current = captured
                        var ms = 0.0
                        if (op.rollback) {
                            tt.execute { st -> val t = System.nanoTime(); rows = op.fn(i); ms = (System.nanoTime() - t) / 1e6; st.setRollbackOnly() }
                        } else { val t = System.nanoTime(); rows = op.fn(i); ms = (System.nanoTime() - t) / 1e6 }
                        log.current = null
                        if (i == 0 && ms > 2000 && wu > 1) { wu = 1; reps = 5; note = "slow(>2s): warmup 1, reps 5" }
                        if (i >= wu) times += ms
                        if (captured.isNotEmpty()) { sqlOut.appendLine("## ${op.id}"); captured.forEach { sqlOut.appendLine("[maxRows=${it.maxRows}] ${it.sql}") }; sqlOut.appendLine() }
                        i++
                    }
                    val s = times.sorted()
                    csv.appendLine("${op.id},%.3f,%.3f,%.3f,%.3f,$rows,${times.size},$note".format(Bench.percentile(s, .5), Bench.percentile(s, .95), s.first(), s.last()))
                    println("%-24s median %9.3f ms  p95 %9.3f  rows %d %s".format(op.id, Bench.percentile(s, .5), Bench.percentile(s, .95), rows, note))
                } catch (e: Exception) {
                    log.current = null
                    val msg = (e.cause ?: e).toString().lines().first().replace(',', ';')
                    csv.appendLine("${op.id},,,,,,0,ERROR $msg"); println("%-24s ERROR %s".format(op.id, msg))
                }
            }
            // which dialect class the repository picked (lazy fields of the real repository, resolved by the calls above)
            sqlOut.insert(0, describeDialect(repo) + "\n")
            // cleanup rows created by the committed operations
            c.createStatement().use { st -> st.executeUpdate("DELETE FROM outbox_record WHERE record_key LIKE 'order-repo-%' OR record_key LIKE 'order-repodel-%'") }
            File(outDir, "timings.csv").writeText(csv.toString())
            File(outDir, "sql.txt").writeText(sqlOut.toString())
            Env.write(cm, o, c, File(outDir, "env.txt"))
        }
        w.close(); hikari.close()
    }
}
