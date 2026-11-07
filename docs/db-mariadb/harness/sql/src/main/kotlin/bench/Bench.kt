package bench

import java.io.File
import java.sql.Connection
import java.sql.SQLException

class Opts(args: List<String>) {
    private val m = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            require(a.startsWith("--")) { "unexpected argument $a" }
            val k = a.removePrefix("--")
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) { m[k] = args[i + 1]; i += 2 } else { flags += k; i++ }
        }
    }
    fun get(k: String, def: String? = null) = m[k] ?: def ?: error("missing --$k")
    fun opt(k: String) = m[k]
    fun int(k: String, def: Int) = m[k]?.toInt() ?: def
    fun has(k: String) = k in flags
}

class Common(o: Opts) {
    val dialect = Dialect.of(o.get("dialect"))
    val variant = o.get("variant")
    /** Base catalog directory (`catalog/<catalogVariant>/<dialect>`); defaults to the variant name. Experiments: `--variant E01a --catalog-variant main --queries <overrides>`. */
    val catalogVariant = o.opt("catalog-variant") ?: variant
    val worktree = File(o.get("worktree", "")).takeIf { o.opt("worktree") != null }
    val ddl: File? = o.opt("ddl")?.let { File(it) }
    val queries: File? = o.opt("queries")?.let { File(it) }
    val scenario = o.get("scenario", "S1")
    val label = o.opt("label") ?: scenario
    val schema = o.opt("schema") ?: "run"
    val catalogRoot = File(o.opt("catalog") ?: "catalog")
    val resultsRoot = File(o.opt("results") ?: "../results")
    val owned = ownedPartitions(o.opt("owned") ?: "all")
    val batch = o.int("batch", 10)
    val reps = o.int("reps", 20)
    val warmup = o.int("warmup", 5)
    val outDir get() = File(resultsRoot, "$variant/${dialect.id}/$label").also { it.mkdirs() }

    fun ddlText(): String {
        val f = ddl ?: File(requireNotNull(worktree) { "--worktree or --ddl required" },
            "namastack-outbox-jdbc/src/main/resources/schema/${dialect.id}/outbox-tables.sql")
        return f.readText()
    }
}

object Bench {
    fun prepare(cm: Common) {
        println("prepare ${cm.variant}/${cm.dialect.id} schema=${cm.schema} ddl=${cm.ddl ?: "worktree"}")
        Loader.prepare(cm.dialect, cm.schema, cm.ddlText())
    }

    fun percentile(sorted: List<Double>, p: Double): Double = sorted[minOf(sorted.size - 1, Math.ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]

    class ExecResult(val rows: Int, val data: List<List<String?>>?, val cols: List<String> = emptyList())

    /** Runs one statement the way the repository does: new PreparedStatement, setMaxRows, bound parameters. */
    fun exec(c: Connection, q: CatalogQuery, p: Map<String, Any?>, maxRows: Int, collect: Boolean = false): ExecResult {
        val (sql, values) = NamedSql.expand(q.sql, p)
        c.prepareStatement(sql).use { ps ->
            NamedSql.bind(ps, values)
            if (maxRows > 0) ps.maxRows = maxRows
            val armed = Limit.arm(q.id, ps)
            try {
                if (q.kind == "read") {
                    ps.executeQuery().use { rs ->
                        val n = rs.metaData.columnCount; var rows = 0; val data = if (collect) mutableListOf<List<String?>>() else null
                        while (rs.next()) { rows++; val r = (1..n).map { rs.getString(it) }; data?.add(r) }
                        return ExecResult(rows, data, (1..n).map { rs.metaData.getColumnLabel(it).lowercase() })
                    }
                }
                return ExecResult(ps.executeUpdate(), null)
            } catch (e: SQLException) {
                if (armed?.fired == true) throw SQLException(armed.message(), e)
                throw e
            } finally { armed?.close() }
        }
    }

    /** PostgreSQL: (custom_plans, generic_plans) of the session's server-prepared statement for this query, or null (not server-prepared / PG < 14). */
    fun planCounts(c: Connection, q: CatalogQuery, p: Map<String, Any?>): Pair<Long, Long>? = try {
        val (sql, _) = NamedSql.expand(q.sql, p)
        var n = 0; val want = sql.map { if (it == '?') "$" + (++n) else it.toString() }.joinToString("").replace(Regex("\\s+"), " ").trim()
        var res: Pair<Long, Long>? = null
        c.createStatement().use { st -> st.executeQuery("SELECT statement, custom_plans, generic_plans FROM pg_prepared_statements").use { rs ->
            while (rs.next()) if (rs.getString(1).replace(Regex("\\s+"), " ").trim() == want) { val cur = res ?: (0L to 0L); res = (cur.first + rs.getLong(2)) to (cur.second + rs.getLong(3)) } } }
        res
    } catch (e: Exception) { null }

    fun maxRowsFor(q: CatalogQuery, ctx: Ctx): Int = when (q.maxRows) { "none" -> 0; else -> ctx.limitFor(q) }

    /** Time one query: warmup + reps, writes inside a rolled-back transaction (timer stops before rollback). */
    /** DB-only times (execute + fetch, without the client sort) of the last `timeQuery` run when `R3.applies(q)`. */
    @Volatile var dbOnly: List<Double> = emptyList()

    fun timeQuery(c: Connection, q: CatalogQuery, ctx: Ctx, warmup: Int, reps: Int, afterWarmup: (() -> Unit)? = null): Triple<List<Double>, Int, String> {
        val times = mutableListOf<Double>(); var rows = 0; var note = ""
        val db = mutableListOf<Double>(); dbOnly = emptyList()
        var w = warmup; var r = reps
        val write = q.kind != "read"
        val total = { w + r }
        var i = 0
        while (i < total()) {
            if (i == w && afterWarmup != null) afterWarmup()
            val p = ctx.paramsFor(q, i)
            val mr = maxRowsFor(q, ctx)
            if (write) c.autoCommit = false else c.autoCommit = true
            val t = System.nanoTime()
            var dbMs = 0.0
            val res = if (R3.applies(q)) { // fetch, then group + sort in the JVM inside the timed section
                val r0 = exec(c, q, p, mr, collect = true); dbMs = (System.nanoTime() - t) / 1e6; R3.sort(r0); r0
            } else exec(c, q, p, mr)
            val ms = (System.nanoTime() - t) / 1e6
            if (write) c.rollback()
            rows = res.rows
            if (i == 0 && ms > 30000) { times += ms; return Triple(times, rows, "very slow (>30s): 1 execution") }
            if (i == 0 && ms > 2000 && w > 1) { w = 1; r = 5; note = "slow(>2s): warmup 1, reps 5" }
            if (i >= w) { times += ms; if (R3.applies(q)) db += dbMs }
            i++
        }
        dbOnly = db
        return Triple(times, rows, note)
    }

    /** Writes the plan files; PostgreSQL: returns (planning ms, execution ms) from the EXPLAIN ANALYZE JSON, else null. */
    fun plan(d: Dialect, c: Connection, q: CatalogQuery, p: Map<String, Any?>, maxRows: Int, dir: File, plain: Boolean = false, rows: Int = -1): Pair<Double, Double>? {
        var times: Pair<Double, Double>? = null
        val (sql, values) = NamedSql.expand(q.sql, p)
        val write = q.kind != "read"
        try {
            c.autoCommit = !write
            if (plain && d != Dialect.POSTGRES && d != Dialect.H2) { planPlain(d, c, NamedSql.literalize(d, sql, values), q, dir, maxRows, rows); return null }
            when (d) {
                Dialect.POSTGRES -> {
                    for ((ext, fmt) in listOf("json" to "FORMAT JSON", "txt" to "FORMAT TEXT")) {
                        c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, SETTINGS, $fmt) $sql").use { ps ->
                            NamedSql.bind(ps, values)
                            ps.executeQuery().use { rs -> val sb = StringBuilder(); while (rs.next()) sb.appendLine(rs.getString(1)); File(dir, "${q.id}.$ext").writeText(sb.toString())
                                if (ext == "json") { val pl = Regex("\"Planning Time\": ([0-9.]+)").find(sb)?.groupValues?.get(1)?.toDouble(); val ex = Regex("\"Execution Time\": ([0-9.]+)").find(sb)?.groupValues?.get(1)?.toDouble()
                                    if (pl != null && ex != null) times = pl to ex } }
                        }
                    }
                    if (Dialect.pgSessionSql.any { it.contains("plan_cache_mode") } && !write) { // generic plan with actual rows: PREPARE + EXPLAIN ANALYZE EXECUTE -> plans/<id>.generic.json|txt
                        var n = 0; val gsql = sql.map { if (it == '?') "$" + (++n) else it.toString() }.joinToString("")
                        val lits = values.joinToString(", ") { NamedSql.literalize(d, "?", listOf(it)) }
                        try {
                            c.createStatement().use { st ->
                                st.execute("PREPARE gp_plan AS $gsql")
                                try {
                                    for ((ext, fmt) in listOf("json" to "FORMAT JSON", "txt" to "FORMAT TEXT"))
                                        st.executeQuery("EXPLAIN (ANALYZE, BUFFERS, SETTINGS, $fmt) EXECUTE gp_plan($lits)").use { rs -> val sb = StringBuilder(); while (rs.next()) sb.appendLine(rs.getString(1)); File(dir, "${q.id}.generic.$ext").writeText(sb.toString()) }
                                } finally { st.execute("DEALLOCATE gp_plan") }
                            }
                        } catch (e: Exception) { File(dir, "${q.id}.generic.error.txt").writeText(e.toString()) }
                    }
                }
                Dialect.MYSQL -> {
                    val lit = NamedSql.literalize(d, sql, values)
                    val txt = try { runExplain(c, "EXPLAIN ANALYZE $lit") } catch (e: SQLException) { "EXPLAIN ANALYZE unsupported here (${e.message}); plain EXPLAIN FORMAT=TREE\n" + runExplain(c, "EXPLAIN FORMAT=TREE $lit") }
                    File(dir, "${q.id}.txt").writeText(txt)
                }
                // same row limit as the MariaDB driver applies for setMaxRows (SET STATEMENT SQL_SELECT_LIMIT=n FOR ...), so main plans are not shown unlimited
                Dialect.MARIADB -> Maria.capture(c, q, NamedSql.literalize(d, sql, values), maxRows, rows, dir, executing = true)
                Dialect.H2 -> File(dir, "${q.id}.txt").writeText(runExplain(c, "EXPLAIN ANALYZE " + NamedSql.literalize(d, sql, values)))
                Dialect.SQLSERVER -> {
                    val lit = NamedSql.literalize(d, sql, values)
                    c.createStatement().use { st ->
                        st.execute("SET STATISTICS XML ON; SET STATISTICS IO ON")
                        var has = st.execute(lit); val xml = StringBuilder()
                        while (true) {
                            if (has) st.resultSet.use { rs ->
                                if (rs.metaData.columnCount == 1 && rs.metaData.getColumnName(1).startsWith("Microsoft SQL Server")) while (rs.next()) xml.append(rs.getString(1))
                            }
                            else if (st.updateCount == -1) break
                            has = st.getMoreResults()
                        }
                        val io = StringBuilder(); var w = st.warnings; while (w != null) { io.appendLine(w.message); w = w.nextWarning }
                        File(dir, "${q.id}.xml").writeText(xml.toString()); File(dir, "${q.id}.io.txt").writeText(io.toString())
                        st.execute("SET STATISTICS XML OFF; SET STATISTICS IO OFF")
                    }
                }
                Dialect.ORACLE -> {
                    var lit = NamedSql.literalize(d, sql, values)
                    lit = if (Regex("/\\*\\+").containsMatchIn(lit)) lit.replaceFirst("/*+", "/*+ gather_plan_statistics")
                    else lit.replaceFirst(Regex("^(SELECT|INSERT|UPDATE|DELETE)", RegexOption.IGNORE_CASE), "$1 /*+ gather_plan_statistics */")
                    c.createStatement().use { st ->
                        if (maxRows > 0) st.maxRows = maxRows
                        if (q.kind == "read") st.executeQuery(lit).use { rs -> while (rs.next()) { } } else st.executeUpdate(lit)
                        val sb = StringBuilder()
                        st.executeQuery("SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))").use { rs -> while (rs.next()) sb.appendLine(rs.getString(1)) }
                        File(dir, "${q.id}.txt").writeText(sb.toString())
                    }
                }
            }
        } catch (e: Exception) {
            File(dir, "${q.id}.error.txt").writeText(e.toString())
        } finally {
            if (write) try { c.rollback() } catch (_: Exception) {}
            c.autoCommit = true
        }
        return times
    }

    /** Non-executing plan for very slow queries (first execution > 30 s). */
    private fun planPlain(d: Dialect, c: Connection, lit: String, q: CatalogQuery, dir: File, maxRows: Int = 0, rows: Int = -1) {
        when (d) {
            Dialect.MYSQL -> File(dir, "${q.id}.txt").writeText("plain EXPLAIN (query not executed)\n" + runExplain(c, "EXPLAIN FORMAT=TREE $lit"))
            Dialect.MARIADB -> Maria.capture(c, q, lit, maxRows, rows, dir, executing = false)
            Dialect.SQLSERVER -> c.createStatement().use { st ->
                st.execute("SET SHOWPLAN_XML ON")
                try { st.executeQuery(lit).use { rs -> val sb = StringBuilder(); while (rs.next()) sb.append(rs.getString(1)); File(dir, "${q.id}.xml").writeText(sb.toString()) } }
                finally { st.execute("SET SHOWPLAN_XML OFF") }
            }
            Dialect.ORACLE -> c.createStatement().use { st ->
                st.execute("EXPLAIN PLAN FOR $lit")
                val sb = StringBuilder()
                st.executeQuery("SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)").use { rs -> while (rs.next()) sb.appendLine(rs.getString(1)) }
                File(dir, "${q.id}.txt").writeText(sb.toString())
            }
            Dialect.H2, Dialect.POSTGRES -> File(dir, "${q.id}.txt").writeText("plain EXPLAIN (query not executed)\n" + runExplain(c, "EXPLAIN $lit"))
        }
    }

    private fun runExplain(c: Connection, sql: String): String {
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> val sb = StringBuilder(); while (rs.next()) sb.appendLine((1..rs.metaData.columnCount).joinToString("\t") { rs.getString(it) ?: "" }); return sb.toString() }
        }
    }

    fun run(cm: Common, o: Opts) {
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        val only = o.opt("only")?.split(",")?.toSet()
        val groups = o.opt("groups")?.split(",")?.toSet()
        val dir = cm.outDir
        File(dir, "plans").mkdirs()
        val skipPlans = o.has("no-plans")
        cm.dialect.connect(cm.schema).use { c ->
            val maria = cm.dialect == Dialect.MARIADB
            if (maria) { Maria.settleHistory(c); if (!o.has("no-prime")) Maria.primeNote = Maria.prime(c) }
            val hcsv = StringBuilder(Maria.HANDLER_HEADER + "\n")
            val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, cm.warmup + cm.reps + 5)
            val adminOnly = cm.scenario.uppercase() in setOf("S1", "S3")
            val csv = StringBuilder("query_id,median_ms,p95_ms,min_ms,max_ms,rows,reps,note\n")
            // PostgreSQL: which plan type the timed reps used (pg_prepared_statements counters) -> plancache.csv
            val pcsv = StringBuilder("query_id,custom_timed,generic_timed,custom_total,generic_total\n")
            // PostgreSQL: planning vs execution time of the captured EXPLAIN ANALYZE (custom plan, first parameter set) -> plantimes.csv
            val ptcsv = StringBuilder("query_id,planning_ms,execution_ms\n")
            // --pages-client-sort: DB-only (execute + fetch) vs total (incl. group + sort) -> pages-clientsort.csv
            val csortcsv = StringBuilder("query_id,db_only_median_ms,db_only_p95_ms,total_median_ms,total_p95_ms\n")
            for (id in Catalog.ORDER) {
                if (only != null && id !in only) continue
                val q = cat[id]
                if (groups != null && q != null && q.group !in groups) continue
                if (q == null) { csv.appendLine("$id,,,,,,0,n/a"); println("%-24s n/a".format(id)); continue }
                if (id in setOf("find_pending_all", "find_failed_all") && !adminOnly && !o.has("all-admin")) {
                    csv.appendLine("$id,,,,,,0,skipped (unbounded; S1/S3 only)"); continue
                }
                try {
                    if (id == "pages") ctx.pageKeys = o.opt("pages-keys-file")?.let { f -> File(f).readLines().map { it.trim() }.filter { it.isNotEmpty() } } ?: strictKeys(c, cat, ctx)
                    val pg = cm.dialect == Dialect.POSTGRES
                    var pre: Pair<Long, Long>? = null
                    val (times, rows, note) = timeQuery(c, q, ctx, cm.warmup, cm.reps, if (pg) ({ pre = planCounts(c, q, ctx.paramsFor(q, 0)) }) else null)
                    if (pg) { val post = planCounts(c, q, ctx.paramsFor(q, 0)); val a = pre ?: (0L to 0L)
                        pcsv.appendLine(if (post == null) "$id,,,,"  else "$id,${post.first - a.first},${post.second - a.second},${post.first},${post.second}") }
                    val s = times.sorted()
                    csv.appendLine("$id,%.3f,%.3f,%.3f,%.3f,$rows,${times.size},$note".format(percentile(s, 0.5), percentile(s, 0.95), s.first(), s.last()))
                    println("%-24s median %9.3f ms  p95 %9.3f  rows %d %s".format(id, percentile(s, 0.5), percentile(s, 0.95), rows, note))
                    if (R3.applies(q) && dbOnly.isNotEmpty()) { val d = dbOnly.sorted(); csortcsv.appendLine("$id,%.3f,%.3f,%.3f,%.3f".format(percentile(d, .5), percentile(d, .95), percentile(s, .5), percentile(s, .95))) }
                    if (maria && !note.startsWith("very slow")) try { hcsv.appendLine(Maria.handlerRow(c, q, ctx.paramsFor(q, 0), maxRowsFor(q, ctx))) } catch (e: Exception) { println("  handlers failed for $id: ${e.message?.lines()?.first()}"); try { c.rollback() } catch (_: Exception) {}; c.autoCommit = true }
                    if (!skipPlans) plan(cm.dialect, c, q, ctx.paramsFor(q, 0), maxRowsFor(q, ctx), File(dir, "plans"), note.startsWith("very slow"), rows)?.let { ptcsv.appendLine("$id,%.3f,%.3f".format(it.first, it.second)) }
                } catch (e: Exception) {
                    try { c.rollback() } catch (_: Exception) {}
                    c.autoCommit = true
                    csv.appendLine("$id,,,,,,0,ERROR ${e.message?.lines()?.first()?.replace(',', ';')}")
                    println("%-24s ERROR %s".format(id, e.message?.lines()?.first()))
                    if (e.message?.startsWith("stopped at limit") == true && !skipPlans) try { // plan without executing, to see why it was stopped
                        val (sql, values) = NamedSql.expand(q.sql, ctx.paramsFor(q, 0))
                        planPlain(cm.dialect, c, NamedSql.literalize(cm.dialect, sql, values), q, File(dir, "plans"), maxRowsFor(q, ctx))
                    } catch (e2: Exception) { File(dir, "plans/$id.error.txt").writeText(e2.toString()) }
                }
            }
            File(dir, "timings.csv").writeText(csv.toString())
            if (maria) File(dir, "handlers.csv").writeText(hcsv.toString())
            if (cm.dialect == Dialect.POSTGRES) { File(dir, "plancache.csv").writeText(pcsv.toString()); File(dir, "plantimes.csv").writeText(ptcsv.toString()) }
            if (R3.pagesClientSort) File(dir, "pages-clientsort.csv").writeText(csortcsv.toString())
            Env.write(cm, o, c, File(dir, "env.txt"))
        }
    }

    fun strictKeys(c: Connection, cat: Map<String, CatalogQuery>, ctx: Ctx): List<String> {
        val ks = cat["keys_strict"] ?: return emptyList()
        val res = exec(c, ks, ctx.paramsFor(ks, 0), maxRowsFor(ks, ctx), collect = true)
        val keys = res.data!!.map { it[0]!! }
        if (keys.isNotEmpty()) return keys
        val kn = cat["keys_nonstrict"] ?: return emptyList()
        return exec(c, kn, ctx.paramsFor(kn, 0), maxRowsFor(kn, ctx), collect = true).data!!.map { it[0]!! }
    }
}

object Env {
    private fun sh(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start(); val s = p.inputStream.bufferedReader().readText(); p.waitFor(); s.trim()
    } catch (e: Exception) { "n/a (${e.message})" }

    fun write(cm: Common, o: Opts, c: Connection, f: File) {
        val d = cm.dialect
        val sb = StringBuilder()
        sb.appendLine("date: ${java.time.Instant.now()}")
        sb.appendLine("variant: ${cm.variant}")
        sb.appendLine("worktree: ${cm.worktree}")
        sb.appendLine("git_sha: ${cm.worktree?.let { sh("git", "-C", it.path, "rev-parse", "HEAD") } ?: "n/a"}")
        sb.appendLine("git_dirty_files: ${cm.worktree?.let { sh("git", "-C", it.path, "status", "--porcelain", "--", ".", ":!docs").lines().filter { l -> l.isNotBlank() }.joinToString("; ") } ?: "n/a"}")
        sb.appendLine("ddl: ${cm.ddl ?: "worktree schema/${d.id}/outbox-tables.sql"}")
        sb.appendLine("ddl_sha256: ${sh("sha256sum", (cm.ddl ?: File(cm.worktree!!, "namastack-outbox-jdbc/src/main/resources/schema/${d.id}/outbox-tables.sql")).path).substringBefore(' ')}")
        sb.appendLine("queries_override: ${cm.queries ?: "none"}")
        sb.appendLine("scenario: ${cm.scenario} label=${cm.label} batch=${cm.batch} owned=${cm.owned.size} partitions warmup=${cm.warmup} reps=${cm.reps}")
        sb.appendLine("lane: ${Dialect.lane} container=${d.container} port=${d.port}")
        sb.appendLine("snapshot: ${d.snapFile.takeIf { it.exists() }?.readText()?.replace("\n", " ")}")
        sb.appendLine("dialect: ${d.id}")
        d.container?.let { ct ->
            sb.appendLine("image: ${sh("docker", "inspect", "--format", "{{.Config.Image}} {{.Image}}", ct)}")
            sb.appendLine("limits: ${sh("docker", "inspect", "--format", "nano_cpus={{.HostConfig.NanoCpus}} cpuset={{.HostConfig.CpusetCpus}} memory_bytes={{.HostConfig.Memory}} shm_bytes={{.HostConfig.ShmSize}}", ct)}")
            sb.appendLine("container_cmd_env: ${sh("docker", "inspect", "--format", "{{.Config.Cmd}} {{.Args}}", ct)}")
        }
        sb.appendLine("db_version: ${c.metaData.databaseProductVersion.lines().first()}")
        sb.appendLine("jdbc_driver: ${c.metaData.driverName} ${c.metaData.driverVersion}")
        sb.appendLine("jvm: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} tz=${System.getProperty("user.timezone")}")
        sb.appendLine("host: ${sh("nproc")} cpus, ${sh("sh", "-c", "grep MemTotal /proc/meminfo")}")
        if (d == Dialect.POSTGRES) try {
            sb.appendLine("non_default_settings:")
            c.createStatement().use { st -> st.executeQuery("SELECT name, setting, unit, source FROM pg_settings WHERE source NOT IN ('default','override') ORDER BY name").use { rs ->
                while (rs.next()) sb.appendLine("  ${rs.getString(1)} = ${rs.getString(2)} ${rs.getString(3) ?: ""} (${rs.getString(4)})") } }
            c.createStatement().use { st -> st.executeQuery("SELECT name, setting, unit FROM pg_settings WHERE name IN ('shared_buffers','work_mem','effective_cache_size','max_parallel_workers_per_gather','random_page_cost','jit','autovacuum')").use { rs ->
                sb.appendLine("key_settings:"); while (rs.next()) sb.appendLine("  ${rs.getString(1)} = ${rs.getString(2)} ${rs.getString(3) ?: ""}") } }
        } catch (_: Exception) {}
        if (d == Dialect.POSTGRES && Dialect.pgSessionSql.isNotEmpty()) sb.appendLine("session_init: ${Dialect.pgSessionSql.joinToString("; ")}")
        if (d == Dialect.MARIADB) sb.append(Maria.envBlock(c))
        sb.appendLine("--- sizes ---"); sb.append(d.sizeReport(c))
        f.writeText(sb.toString())
    }
}

/** Round 3 options: `--pages-client-sort` (see harness/README.md "Changes"). */
object R3 {
    @Volatile var pagesClientSort = false
    /** Client sort applies only to a `pages` statement without ORDER BY; for the `unnest` + `LATERAL` form only the part after `LATERAL` counts (the `ORDER BY t.k` of the key list does not order the rows). */
    fun applies(q: CatalogQuery): Boolean {
        if (!pagesClientSort || q.id != "pages") return false
        val i = q.sql.indexOf("LATERAL", ignoreCase = true)
        return !(if (i >= 0) q.sql.substring(i) else q.sql).contains("ORDER BY", ignoreCase = true)
    }

    /** Group rows by `record_key` (order of first appearance) and sort each group by `created_at` (text, UTC, fraction trimmed: lexicographic = chronological), then `id` with String.compareTo. */
    fun sort(res: Bench.ExecResult): List<List<String?>> {
        val ik = res.cols.indexOf("record_key"); val ic = res.cols.indexOf("created_at"); val ii = res.cols.indexOf("id")
        val groups = LinkedHashMap<String?, MutableList<List<String?>>>()
        for (r in res.data!!) groups.getOrPut(r[ik]) { mutableListOf() }.add(r)
        val cmp = Comparator<List<String?>> { a, b -> val c = a[ic]!!.compareTo(b[ic]!!); if (c != 0) c else a[ii]!!.compareTo(b[ii]!!) }
        val out = ArrayList<List<String?>>(res.rows)
        for (g in groups.values) { g.sortWith(cmp); out.addAll(g) }
        return out
    }
}
