package bench

import java.io.File
import java.sql.Statement
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Per-execution query limit. Off unless `--query-limit-s` or `--limit-from` is given, so runs without them behave as before.
 * `--query-limit-s N`: limit in seconds. `--limit-from <timings.csv>`: for a query with a median in that file, max(`--limit-factor` (2) x median, `--limit-min-s` (60)); other queries use N.
 * A stopped execution is cancelled from a daemon thread and surfaces as an SQLException "stopped at limit ...".
 */
object Limit {
    private var default: Long? = null
    private var factor = 2.0
    private var minS = 60L
    private var medians: Map<String, Double> = emptyMap()

    private val timer by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "query-limit").apply { isDaemon = true } }
    }

    fun init(o: Opts) {
        default = o.opt("query-limit-s")?.toLong()
        factor = o.opt("limit-factor")?.toDouble() ?: 2.0
        minS = o.opt("limit-min-s")?.toLong() ?: 60L
        medians = o.opt("limit-from")?.let { File(it) }?.takeIf { it.isFile }?.readLines()?.drop(1)?.mapNotNull { l ->
            val c = l.split(","); c.getOrNull(1)?.toDoubleOrNull()?.let { c[0] to it }
        }?.toMap() ?: emptyMap()
    }

    fun seconds(id: String): Long? {
        val m = medians[id] ?: return default
        return maxOf(Math.ceil(factor * m / 1000.0).toLong(), minS)
    }

    class Armed(private val id: String, val seconds: Long, private val st: Statement) {
        @Volatile var fired = false; private set
        private var done = false
        private var task: ScheduledFuture<*>? = null
        fun start(run: (Runnable) -> ScheduledFuture<*>) { task = run(Runnable {
            synchronized(this) { if (!done) { fired = true; try { st.cancel() } catch (e: Exception) { println("limit: cancel failed for $id: $e") } } } }) }
        fun close() { synchronized(this) { done = true }; task?.cancel(false) }
        fun message() = "stopped at limit $seconds s ($id)"
    }

    fun arm(id: String, st: Statement): Armed? {
        val s = seconds(id) ?: return null
        return Armed(id, s, st).also { a -> a.start { r -> timer.schedule(r, s, TimeUnit.SECONDS) } }
    }

    /** Checks that this dialect's driver stops a long query at the limit. Run with a small `--query-limit-s`. */
    fun check(d: Dialect) {
        val sql = when (d) {
            Dialect.POSTGRES -> "SELECT COUNT(*) FROM generate_series(1, 100000) a, generate_series(1, 100000) b"
            Dialect.SQLSERVER -> "SELECT COUNT_BIG(*) FROM sys.all_columns a, sys.all_columns b, sys.all_columns c, sys.all_columns d"
            Dialect.ORACLE -> "SELECT COUNT(*) FROM all_objects a, all_objects b, all_objects c"
            else -> "SELECT COUNT(*) FROM information_schema.columns a, information_schema.columns b, information_schema.columns c, information_schema.columns d, information_schema.columns e"
        }
        val t = System.nanoTime()
        val out = d.admin().use { c ->
            try { Bench.exec(c, CatalogQuery("limitcheck", sql, mapOf("kind" to "read"), File("limitcheck")), emptyMap(), 0); "NOT STOPPED" }
            catch (e: Exception) { e.message ?: e.toString() }
        }
        val s = (System.nanoTime() - t) / 1e9
        println("limitcheck ${d.id}: %.1f s: %s".format(s, out))
        if (!out.startsWith("stopped at limit") || s > 60) System.exit(1)
        System.exit(0)
    }
}
