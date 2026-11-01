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
            if (q.kind == "read") {
                ps.executeQuery().use { rs ->
                    val n = rs.metaData.columnCount; var rows = 0; val data = if (collect) mutableListOf<List<String?>>() else null
                    while (rs.next()) { rows++; val r = (1..n).map { rs.getString(it) }; data?.add(r) }
                    return ExecResult(rows, data, (1..n).map { rs.metaData.getColumnLabel(it).lowercase() })
                }
            }
            return ExecResult(ps.executeUpdate(), null)
        }
    }

    fun maxRowsFor(q: CatalogQuery, ctx: Ctx): Int = when (q.maxRows) { "none" -> 0; else -> ctx.limitFor(q) }

    /** Time one query: warmup + reps, writes inside a rolled-back transaction (timer stops before rollback). */
    fun timeQuery(c: Connection, q: CatalogQuery, ctx: Ctx, warmup: Int, reps: Int): Triple<List<Double>, Int, String> {
        val times = mutableListOf<Double>(); var rows = 0; var note = ""
        var w = warmup; var r = reps
        val write = q.kind != "read"
        val total = { w + r }
        var i = 0
        while (i < total()) {
            val p = ctx.paramsFor(q, i)
            val mr = maxRowsFor(q, ctx)
            if (write) c.autoCommit = false else c.autoCommit = true
            val t = System.nanoTime()
            val res = exec(c, q, p, mr)
            val ms = (System.nanoTime() - t) / 1e6
            if (write) c.rollback()
            rows = res.rows
            if (i == 0 && ms > 30000) { times += ms; return Triple(times, rows, "very slow (>30s): 1 execution") }
            if (i == 0 && ms > 2000 && w > 1) { w = 1; r = 5; note = "slow(>2s): warmup 1, reps 5" }
            if (i >= w) times += ms
            i++
        }
        return Triple(times, rows, note)
    }

    fun plan(d: Dialect, c: Connection, q: CatalogQuery, p: Map<String, Any?>, maxRows: Int, dir: File, plain: Boolean = false) {
        val (sql, values) = NamedSql.expand(q.sql, p)
        val write = q.kind != "read"
        try {
            c.autoCommit = !write
            if (plain && d != Dialect.POSTGRES && d != Dialect.H2) { planPlain(d, c, NamedSql.literalize(d, sql, values), q, dir); return }
            when (d) {
                Dialect.POSTGRES -> {
                    for ((ext, fmt) in listOf("json" to "FORMAT JSON", "txt" to "FORMAT TEXT")) {
                        c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, SETTINGS, $fmt) $sql").use { ps ->
                            NamedSql.bind(ps, values)
                            ps.executeQuery().use { rs -> val sb = StringBuilder(); while (rs.next()) sb.appendLine(rs.getString(1)); File(dir, "${q.id}.$ext").writeText(sb.toString()) }
                        }
                    }
                }
                Dialect.MYSQL -> {
                    val lit = NamedSql.literalize(d, sql, values)
                    val txt = try { runExplain(c, "EXPLAIN ANALYZE $lit") } catch (e: SQLException) { "EXPLAIN ANALYZE unsupported here (${e.message}); plain EXPLAIN FORMAT=TREE\n" + runExplain(c, "EXPLAIN FORMAT=TREE $lit") }
                    File(dir, "${q.id}.txt").writeText(txt)
                }
                Dialect.MARIADB -> File(dir, "${q.id}.json").writeText(runExplain(c, "ANALYZE FORMAT=JSON " + NamedSql.literalize(d, sql, values)))
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
    }

    /** Non-executing plan for very slow queries (first execution > 30 s). */
    private fun planPlain(d: Dialect, c: Connection, lit: String, q: CatalogQuery, dir: File) {
        when (d) {
            Dialect.MYSQL -> File(dir, "${q.id}.txt").writeText("plain EXPLAIN (query not executed)\n" + runExplain(c, "EXPLAIN FORMAT=TREE $lit"))
            Dialect.MARIADB -> File(dir, "${q.id}.json").writeText(runExplain(c, "EXPLAIN FORMAT=JSON $lit"))
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
            else -> {}
        }
    }

    private fun runExplain(c: Connection, sql: String): String {
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> val sb = StringBuilder(); while (rs.next()) sb.appendLine((1..rs.metaData.columnCount).joinToString("\t") { rs.getString(it) ?: "" }); return sb.toString() }
        }
    }

    fun run(cm: Common, o: Opts) {
        val cat = Catalog.load(cm.catalogRoot, cm.variant, cm.dialect, cm.queries)
        val only = o.opt("only")?.split(",")?.toSet()
        val groups = o.opt("groups")?.split(",")?.toSet()
        val dir = cm.outDir
        File(dir, "plans").mkdirs()
        val skipPlans = o.has("no-plans")
        cm.dialect.connect(cm.schema).use { c ->
            val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, cm.warmup + cm.reps + 5)
            val adminOnly = cm.scenario.uppercase() in setOf("S1", "S3")
            val csv = StringBuilder("query_id,median_ms,p95_ms,min_ms,max_ms,rows,reps,note\n")
            for (id in Catalog.ORDER) {
                if (only != null && id !in only) continue
                val q = cat[id]
                if (groups != null && q != null && q.group !in groups) continue
                if (q == null) { csv.appendLine("$id,,,,,,0,n/a"); println("%-24s n/a".format(id)); continue }
                if (id in setOf("find_pending_all", "find_failed_all") && !adminOnly && !o.has("all-admin")) {
                    csv.appendLine("$id,,,,,,0,skipped (unbounded; S1/S3 only)"); continue
                }
                try {
                    if (id == "pages") ctx.pageKeys = strictKeys(c, cat, ctx)
                    val (times, rows, note) = timeQuery(c, q, ctx, cm.warmup, cm.reps)
                    val s = times.sorted()
                    csv.appendLine("$id,%.3f,%.3f,%.3f,%.3f,$rows,${times.size},$note".format(percentile(s, 0.5), percentile(s, 0.95), s.first(), s.last()))
                    println("%-24s median %9.3f ms  p95 %9.3f  rows %d %s".format(id, percentile(s, 0.5), percentile(s, 0.95), rows, note))
                    if (!skipPlans) plan(cm.dialect, c, q, ctx.paramsFor(q, 0), maxRowsFor(q, ctx), File(dir, "plans"), note.startsWith("very slow"))
                } catch (e: Exception) {
                    try { c.rollback() } catch (_: Exception) {}
                    c.autoCommit = true
                    csv.appendLine("$id,,,,,,0,ERROR ${e.message?.lines()?.first()?.replace(',', ';')}")
                    println("%-24s ERROR %s".format(id, e.message?.lines()?.first()))
                }
            }
            File(dir, "timings.csv").writeText(csv.toString())
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
        sb.appendLine("snapshot: ${File("build/snap-${d.id}.txt").takeIf { it.exists() }?.readText()?.replace("\n", " ")}")
        sb.appendLine("dialect: ${d.id}")
        if (d.container != null) {
            sb.appendLine("image: ${sh("docker", "inspect", "--format", "{{.Config.Image}} {{.Image}}", d.container)}")
            sb.appendLine("limits: ${sh("docker", "inspect", "--format", "nano_cpus={{.HostConfig.NanoCpus}} memory_bytes={{.HostConfig.Memory}} shm_bytes={{.HostConfig.ShmSize}}", d.container)}")
            sb.appendLine("container_cmd_env: ${sh("docker", "inspect", "--format", "{{.Config.Cmd}} {{.Args}}", d.container)}")
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
        sb.appendLine("--- sizes ---"); sb.append(d.sizeReport(c))
        f.writeText(sb.toString())
    }
}
