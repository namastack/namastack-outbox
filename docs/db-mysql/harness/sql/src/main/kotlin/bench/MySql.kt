package bench

import java.io.File
import java.sql.Connection
import java.sql.SQLException

/**
 * MySQL-specific helpers (the MariaDB ones are in [Maria]; generic InnoDB helpers such as `status`, `flushAll`, `indexSizes`, `prime` are reused from there):
 * history list length from `INNODB_METRICS`, global counters for writecost/concurrency/churn (redo LSN, deadlocks, binlog bytes), binary-log purge,
 * env.txt settings, plan capture (`EXPLAIN ANALYZE` tree, `EXPLAIN FORMAT=JSON`, optimizer trace, summary) and the `maxrowscheck` command.
 */
object MySql {
    /**
     * Plans of queries with `maxrows` != none are captured under `SET SESSION sql_select_limit = n`, because Connector/J 9.7.0 sends `SET sql_select_limit=n` before
     * and `SET sql_select_limit=DEFAULT` after every statement with `setMaxRows(n)` (verified with the general_log, results/validation/general_log.txt).
     * `--no-plan-select-limit` captures without it (experiment M9).
     */
    @Volatile var planSelectLimit = true

    private fun enableMetrics(c: Connection) {
        for (m in listOf("trx_rseg_history_len", "lock_deadlocks")) try { c.createStatement().use { it.execute("SET GLOBAL innodb_monitor_enable = '$m'") } } catch (_: Exception) {}
    }

    private fun metric(c: Connection, name: String): Long =
        Maria.scalar(c, "SELECT count FROM information_schema.INNODB_METRICS WHERE name='$name'")?.toLongOrNull() ?: -1

    /** `trx_rseg_history_len` (MySQL has no `Innodb_history_list_length` status variable). */
    fun historyListLength(c: Connection): Long = metric(c, "trx_rseg_history_len")

    fun settleHistory(c: Connection): String { enableMetrics(c); return Maria.settleHistory(c, hll = ::historyListLength) }

    /** (file_size, allocated_size) of the table's tablespace from `information_schema.INNODB_TABLESPACES`, or null. */
    fun tablespaceBytes(c: Connection): Pair<Long, Long>? = try {
        c.createStatement().use { st -> st.executeQuery("SELECT file_size, allocated_size FROM information_schema.INNODB_TABLESPACES WHERE name = CONCAT(DATABASE(), '/outbox_record')").use { rs ->
            if (rs.next()) rs.getLong(1) to rs.getLong(2) else null } }
    } catch (e: Exception) { null }

    // ---- global counters ----------------------------------------------------------------------------------------------

    val STATUS = listOf("Innodb_os_log_written", "Innodb_redo_log_current_lsn", "Innodb_pages_written", "Innodb_data_written", "Innodb_buffer_pool_read_requests",
        "Innodb_buffer_pool_reads", "Innodb_row_lock_waits", "Innodb_row_lock_time", "Innodb_buffer_pool_pages_dirty")

    /** Sum of the file sizes of `SHOW BINARY LOGS` (0 when binary logging is off). */
    fun binlogBytes(c: Connection): Long = try {
        c.createStatement().use { st -> st.executeQuery("SHOW BINARY LOGS").use { rs -> var s = 0L; while (rs.next()) s += rs.getLong(2); s } }
    } catch (e: Exception) { 0 }

    /** Global InnoDB counters; `Innodb_deadlocks` comes from `INNODB_METRICS.lock_deadlocks`, `binlog_bytes` from `SHOW BINARY LOGS`. */
    fun globals(c: Connection): Map<String, Long> {
        enableMetrics(c)
        val m = linkedMapOf<String, Long>()
        for (g in STATUS) m.putAll(Maria.status(c, true, g))
        for (g in STATUS) m.putIfAbsent(g, 0)
        m["Innodb_deadlocks"] = metric(c, "lock_deadlocks").coerceAtLeast(0)
        m["binlog_bytes"] = binlogBytes(c)
        return m
    }

    fun delta(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> = a.keys.associateWith { (b[it] ?: 0) - (a[it] ?: 0) }

    /**
     * Frees the binary logs (never call inside a timed section): `RESET BINARY LOGS AND GTIDS` (8.4), `RESET MASTER` (older), else
     * `PURGE BINARY LOGS BEFORE NOW()`. The lane's server has no replicas, so deleting all binary logs is safe.
     */
    fun purgeBinlogs(d: Dialect) {
        if (d != Dialect.MYSQL) return
        try {
            d.admin().use { c ->
                c.autoCommit = true
                val before = binlogBytes(c)
                for (sql in listOf("RESET BINARY LOGS AND GTIDS", "RESET MASTER", "PURGE BINARY LOGS BEFORE NOW()")) {
                    try { c.createStatement().use { it.execute(sql) }; println("binary logs: $sql (was ${before / 1048576} MiB, now ${binlogBytes(c) / 1048576} MiB)"); return }
                    catch (_: SQLException) {}
                }
                println("binary logs: could not purge")
            }
        } catch (e: Exception) { println("binary logs: purge failed ${e.message?.lines()?.first()}") }
    }

    // ---- env.txt ------------------------------------------------------------------------------------------------------

    fun envBlock(c: Connection): String {
        val sb = StringBuilder()
        sb.appendLine("--- mysql settings ---")
        for (v in listOf("version", "version_comment", "innodb_stats_persistent", "innodb_stats_persistent_sample_pages", "innodb_stats_auto_recalc", "eq_range_index_dive_limit",
            "range_optimizer_max_mem_size", "optimizer_switch", "optimizer_search_depth", "innodb_buffer_pool_size", "innodb_flush_log_at_trx_commit", "innodb_flush_method",
            "innodb_adaptive_hash_index", "innodb_change_buffering", "innodb_redo_log_capacity", "log_bin", "binlog_format", "binlog_row_image", "sync_binlog", "transaction_isolation",
            "character_set_server", "collation_server", "sql_mode", "innodb_page_size", "explicit_defaults_for_timestamp", "time_zone", "max_binlog_size", "binlog_expire_logs_seconds"))
            sb.appendLine("$v: ${Maria.scalar(c, "SELECT @@global.$v") ?: Maria.scalar(c, "SELECT @@$v") ?: "n/a"}")
        sb.appendLine("session time_zone/sql_select_limit: ${Maria.scalar(c, "SELECT CONCAT(@@session.time_zone, ' / ', @@session.sql_select_limit)")}")
        sb.appendLine("connector_j: ${c.metaData.driverName} ${c.metaData.driverVersion}")
        sb.appendLine("innodb_history_list_length_at_start (trx_rseg_history_len): ${Maria.settleNote}")
        sb.appendLine("buffer_pool_priming: ${Maria.primeNote}")
        sb.appendLine("--- run schema column charset/collation (information_schema.COLUMNS) ---")
        try {
            c.createStatement().use { st -> st.executeQuery("SELECT column_name, column_type, character_set_name, collation_name FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND table_name='outbox_record' AND column_name IN ('id','record_key') ORDER BY ordinal_position").use { rs ->
                while (rs.next()) sb.appendLine("column ${rs.getString(1)}: ${rs.getString(2)} charset=${rs.getString(3)} collation=${rs.getString(4)}") } }
            c.createStatement().use { st -> st.executeQuery("SELECT table_collation FROM information_schema.TABLES WHERE table_schema=DATABASE() AND table_name='outbox_record'").use { rs -> if (rs.next()) sb.appendLine("table_collation: ${rs.getString(1)}") } }
            sb.appendLine("--- histograms (information_schema.COLUMN_STATISTICS) ---")
            var n = 0
            c.createStatement().use { st -> st.executeQuery("SELECT column_name, JSON_UNQUOTE(JSON_EXTRACT(histogram, '$.\"histogram-type\"')), JSON_EXTRACT(histogram, '$.\"number-of-buckets-specified\"') FROM information_schema.COLUMN_STATISTICS WHERE schema_name=DATABASE() AND table_name='outbox_record'").use { rs ->
                while (rs.next()) { n++; sb.appendLine("histogram ${rs.getString(1)}: ${rs.getString(2)} buckets=${rs.getString(3)}") } } }
            if (n == 0) sb.appendLine("histograms: none")
        } catch (e: Exception) { sb.appendLine("column info failed: ${e.message?.lines()?.first()}") }
        return sb.toString()
    }

    // ---- plans --------------------------------------------------------------------------------------------------------

    private fun treeText(c: Connection, sql: String): String = Maria.explainText(c, sql)

    /**
     * Writes `<id>.txt` (EXPLAIN ANALYZE tree, or EXPLAIN FORMAT=TREE when not executing / unsupported), `<id>.json` (EXPLAIN FORMAT=JSON),
     * `<id>.trace.json` (optimizer trace, reads only) and `<id>.summary.txt`. [lit] is the literalized statement (client-side prepared statements send
     * literals as well). With [planSelectLimit] and `maxRows > 0` all of it runs under `SET SESSION sql_select_limit = n`.
     */
    fun capture(c: Connection, q: CatalogQuery, lit: String, maxRows: Int, rows: Int, dir: File, executing: Boolean) {
        val lim = if (planSelectLimit && maxRows > 0) maxRows else 0
        if (lim > 0) c.createStatement().use { it.execute("SET SESSION sql_select_limit = $lim") }
        try {
            var mode = if (executing) "ANALYZE (executed)" else "EXPLAIN FORMAT=TREE (not executed)"
            val tree = if (executing) try { treeText(c, "EXPLAIN ANALYZE $lit") } catch (e: SQLException) {
                mode = "EXPLAIN FORMAT=TREE (not executed; EXPLAIN ANALYZE failed: ${e.message?.lines()?.first()})"
                "EXPLAIN ANALYZE unavailable (${e.message?.lines()?.first()}); plain EXPLAIN FORMAT=TREE\n" + treeText(c, "EXPLAIN FORMAT=TREE $lit")
            } else "plain EXPLAIN (query not executed)\n" + treeText(c, "EXPLAIN FORMAT=TREE $lit")
            if (mode.startsWith("ANALYZE") && tree.contains("<not executable by iterator executor>"))
                mode = "EXPLAIN ANALYZE gave no iterator tree (single-table DML); plan from EXPLAIN FORMAT=JSON (estimates only, not executed by the plan capture)"
            File(dir, "${q.id}.txt").writeText(tree)
            val json = treeText(c, "EXPLAIN FORMAT=JSON $lit")
            File(dir, "${q.id}.json").writeText(json)
            if (q.kind == "read") try { File(dir, "${q.id}.trace.json").writeText(trace(c, lit)) }
            catch (e: Exception) { File(dir, "${q.id}.trace.error.txt").writeText(e.toString()) }
            File(dir, "${q.id}.summary.txt").writeText(summary(q.id, json, tree, mode, lim, rows))
        } finally { if (lim > 0) try { c.createStatement().use { it.execute("SET SESSION sql_select_limit = DEFAULT") } } catch (_: Exception) {} }
    }

    private fun toJson(x: Any?): String = when (x) {
        null -> "null"
        is String -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
        is Double -> if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else x.toString()
        is Map<*, *> -> x.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
        is List<*> -> x.joinToString(",", "[", "]") { toJson(it) }
        else -> x.toString()
    }

    /** Optimizer trace of `EXPLAIN FORMAT=JSON <lit>`; large traces are cut to the considered plans, row estimates and attached conditions. */
    private fun trace(c: Connection, lit: String): String {
        c.createStatement().use { st -> st.execute("SET SESSION optimizer_trace_max_mem_size = 33554432"); st.execute("SET SESSION optimizer_trace = 'enabled=on'") }
        val full: String; val missing: Long
        try {
            treeText(c, "EXPLAIN FORMAT=JSON $lit")
            c.createStatement().use { st -> st.executeQuery("SELECT trace, missing_bytes_beyond_max_mem_size FROM information_schema.OPTIMIZER_TRACE").use { rs -> rs.next(); full = rs.getString(1) ?: ""; missing = rs.getLong(2) } }
        } finally { c.createStatement().use { it.execute("SET SESSION optimizer_trace = 'enabled=off'") } }
        if (full.length <= 60_000 && missing == 0L) return full
        val want = setOf("considered_execution_plans", "rows_estimation", "attaching_conditions_to_tables")
        val out = StringBuilder("{\"note\":\"optimizer trace reduced: full size ${full.length} chars, missing_bytes=$missing; kept ${want.joinToString("/")} (each value cut to 20000 chars)\",\"parts\":[")
        var first = true
        fun walk(n: Any?, path: String) {
            when (n) {
                is Map<*, *> -> for ((k, v) in n) {
                    if (k in want) {
                        var t = toJson(v); val cut = t.length > 20000
                        if (cut) t = toJson(t.substring(0, 20000) + " ...[cut]")
                        if (!first) out.append(','); first = false
                        out.append("{\"path\":").append(toJson("$path/$k")).append(",\"value\":").append(t).append('}')
                    } else walk(v, "$path/$k")
                }
                is List<*> -> n.forEachIndexed { i, v -> walk(v, "$path[$i]") }
            }
        }
        try { walk(Maria.J(full.trim()).value(), "") } catch (e: Exception) { out.append("{\"error\":").append(toJson("trace parse failed: ${e.message}")).append('}') }
        return out.append("]}").toString()
    }

    // ---- summary ------------------------------------------------------------------------------------------------------

    private fun numAny(x: Any?): Double? = when (x) { is Double -> x; is String -> x.toDoubleOrNull(); else -> null }
    private fun fmt(x: Double?) = if (x == null) "-" else if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else "%.3f".format(x)

    private class Node(val depth: Int, val desc: String, val estRows: Double?, val actRows: Double?, val loops: Double?, val never: Boolean)

    private val NODE = Regex("^(\\s*)-> (.*?)\\s+\\(cost=([0-9.e+]+) rows=([0-9.e+]+)\\)(?: \\(actual time=([0-9.]+)\\.\\.([0-9.]+) rows=([0-9.e+]+) loops=([0-9]+)\\)| \\((never executed)\\))?")
    private val ACCESS = Regex("^(Covering index (lookup|scan|range scan|skip scan)|Index (lookup|scan|range scan|skip scan)|Table scan|Single-row (covering )?index lookup|Index range scan|Constant row|Rows fetched)\\b.*? on (\\S+)(?: using (\\S+))?")

    private fun parseTree(tree: String): List<Node> = tree.lines().mapNotNull { l ->
        val m = NODE.find(l) ?: return@mapNotNull null
        Node(m.groupValues[1].length, m.groupValues[2], m.groupValues[4].toDoubleOrNull(), m.groupValues[7].toDoubleOrNull(), m.groupValues[8].toDoubleOrNull(), m.groupValues[9].isNotEmpty())
    }

    /** Short, quotable summary: per table (JSON: access type, key, key parts, estimates; tree: actual rows, loops), filesort/temporary, join strategies, rows examined vs returned. */
    fun summary(id: String, json: String, tree: String, mode: String, selectLimit: Int, rowsReturned: Int): String {
        val sb = StringBuilder()
        sb.appendLine("id: $id   mode: $mode   sql_select_limit: ${if (selectLimit > 0) "$selectLimit (SET SESSION, as Connector/J does for setMaxRows)" else "none (statement without setMaxRows, or --no-plan-select-limit)"}   rows_returned: ${if (rowsReturned >= 0) rowsReturned else "n/a"}")
        val root = try { Maria.J(json.trim()).value() as Map<*, *> } catch (e: Exception) { sb.appendLine("json summary failed: parse error ${e.message}"); null }
        val qb = root?.get("query_block") as? Map<*, *>
        val qcost = ((qb?.get("cost_info") as? Map<*, *>)?.get("query_cost"))
        sb.appendLine("query_cost: ${qcost ?: "-"}")
        var filesort = false; var temp = false; val flags = mutableSetOf<String>()
        fun walk(n: Any?, sel: String) {
            when (n) {
                is Map<*, *> -> {
                    var cur = sel
                    numAny(n["select_id"])?.let { cur = "select#" + fmt(it) }
                    for ((k, v) in n) {
                        when (k) {
                            "using_filesort" -> if (v == true) filesort = true
                            "using_temporary_table" -> if (v == true) temp = true
                            "using_join_buffer" -> flags += "join_buffer(${v})"
                            "using_index_for_skip_scan" -> if (v == true) flags += "index_skip_scan"
                            "using_MRR" -> if (v == true) flags += "mrr"
                            "duplicates_removal" -> flags += "duplicates_removal"
                            "materialized_from_subquery" -> flags += "materialized_subquery"
                            "attached_subqueries" -> flags += "attached_subqueries"
                            "index_merge", "range_checked_for_each_record" -> flags += k.toString()
                        }
                        if (k == "table" && v is Map<*, *> && v.containsKey("table_name")) {
                            val t = v
                            val parts = (t["used_key_parts"] as? List<*>)?.joinToString(",") ?: "-"
                            val ci = (t["cost_info"] as? Map<*, *>)?.get("prefix_cost")
                            sb.appendLine("$cur table=${t["table_name"]} access=${t["access_type"] ?: "-"} key=${t["key"] ?: "-"} key_parts=[$parts] key_len=${t["key_length"] ?: "-"} " +
                                "rows_examined_per_scan=${fmt(numAny(t["rows_examined_per_scan"]))} rows_produced_per_join=${fmt(numAny(t["rows_produced_per_join"]))} filtered=${fmt(numAny(t["filtered"]))} " +
                                "using_index=${if (t["using_index"] == true) "yes" else "no"} attached_condition=${if (t.containsKey("attached_condition")) "yes" else "no"} " +
                                "index_condition=${if (t.containsKey("index_condition")) "yes" else "no"} prefix_cost=${ci ?: "-"}" +
                                (if (t["using_index_for_skip_scan"] == true) " skip_scan=yes" else ""))
                        }
                        walk(v, cur)
                    }
                }
                is List<*> -> n.forEach { walk(it, sel) }
            }
        }
        walk(qb, "select#1")
        // tree: actual rows per table access node
        val nodes = parseTree(tree)
        var examined = 0.0; var haveActual = false
        for (nd in nodes) {
            val m = ACCESS.find(nd.desc) ?: continue
            val total = if (nd.actRows != null && nd.loops != null) nd.actRows * nd.loops else null
            if (total != null) { examined += total; haveActual = true }
            sb.appendLine("access: table=${m.groupValues[5]} index=${m.groupValues[6].ifEmpty { "-" }} node=\"${nd.desc.take(140)}\" est_rows=${fmt(nd.estRows)} actual_rows=${fmt(nd.actRows)} loops=${fmt(nd.loops)} " +
                "rows_read=${if (nd.never) "never executed" else fmt(total)}")
        }
        val lower = tree.lowercase()
        val strat = mutableListOf<String>()
        for ((rx, name) in listOf("anti ?join" to "antijoin", "semi ?join" to "semijoin", "hash join" to "hash join", "firstmatch" to "FirstMatch", "loosescan" to "LooseScan",
            "weedout" to "duplicate weedout", "materializ" to "Materialize", "skip scan" to "index skip scan", "sort row ids" to "index merge (sort row ids)", "intersect" to "index merge (intersect)",
            "union" to "union", "nested loop" to "nested loop", "aggregate" to "aggregate", "stream results" to "stream results", "limit" to "limit"))
            if (Regex(rx).containsInStr(lower)) strat += name
        if (lower.contains("sort:") || lower.contains("filesort")) filesort = true
        if (lower.contains("temporary table") || lower.contains("<temporary>")) temp = true
        if (flags.contains("index_skip_scan") && "index skip scan" !in strat) strat += "index skip scan"
        sb.appendLine("filesort: ${if (filesort) "yes" else "no"}   temporary: ${if (temp) "yes" else "no"}   strategies: ${if (strat.isEmpty()) "-" else strat.joinToString(",")}   json_flags: ${if (flags.isEmpty()) "-" else flags.joinToString(",")}")
        val rootNode = nodes.firstOrNull()
        val rootRows = rootNode?.let { n -> if (n.actRows != null && n.loops != null) n.actRows * n.loops else null }
        sb.appendLine("rows_examined_total (tree, sum of table access nodes): ${if (haveActual) fmt(examined) else "n/a (no actuals)"}   rows_returned: ${if (rowsReturned >= 0) rowsReturned else "n/a"}   root_node_actual_rows: ${fmt(rootRows)}")
        return sb.toString()
    }

    private fun Regex.containsInStr(s: String) = this.containsMatchIn(s)

    // ---- maxrowscheck ---------------------------------------------------------------------------------------------------

    /**
     * `maxrowscheck --dialect mysql --schema run --scenario S1 --out FILE`: records with the server's `general_log` (TABLE) what the server receives for
     * (1) a stand-in for a `main`-style statement (no LIMIT in the SQL) with `setMaxRows(5)`, (2) the `my-opt` key query (SQL `LIMIT ?` + `setMaxRows(10)`),
     * (3) the `my-opt` `oldest_due_strict` (`setMaxRows(1)`), (4) the `my-main` key query (SQL `LIMIT ?`, no `setMaxRows`), plus session `Handler_read_*` deltas.
     */
    fun maxRowsCheck(o: Opts) {
        val d = Dialect.of(o.get("dialect")); require(d == Dialect.MYSQL) { "maxrowscheck: mysql only" }
        val schema = o.opt("schema") ?: "run"; val scenario = o.get("scenario", "S1")
        val root = File(o.opt("catalog") ?: "catalog")
        val opt = Catalog.load(root, "my-opt", d, null); val main = Catalog.load(root, "my-main", d, null)
        val sb = StringBuilder()
        d.admin().use { adm ->
            adm.autoCommit = true
            val oldOut = Maria.scalar(adm, "SELECT @@global.log_output") ?: "FILE"
            adm.createStatement().use { it.execute("SET GLOBAL general_log = 'OFF'"); it.execute("TRUNCATE TABLE mysql.general_log"); it.execute("SET GLOBAL log_output = 'TABLE'"); it.execute("SET GLOBAL general_log = 'ON'") }
            try {
                d.connect(schema).use { c ->
                    val tid = Maria.scalar(c, "SELECT CONNECTION_ID()")!!.toLong()
                    val ctx = Ctx(d, c, scenario, 10, (0 until 256).toList(), 30)
                    fun marker(n: String) = c.createStatement().use { it.execute("DO 'case $n'") }
                    fun hand() = Maria.status(c, false, "Handler_read_next") + Maria.status(c, false, "Handler_read_key") + Maria.status(c, false, "Handler_read_rnd_next")
                    class Case(val name: String, val q: CatalogQuery, val mr: Int, val p: Map<String, Any?>)
                    val standIn = CatalogQuery("standin_no_limit", "SELECT id FROM outbox_record WHERE status = :status", mapOf("kind" to "read", "group" to "HOT", "maxrows" to "batchSize"), File("standin"))
                    val cases = listOf(
                        Case("1 stand-in without SQL LIMIT, setMaxRows(5)", standIn, 5, mapOf("status" to "COMPLETED")),
                        Case("2 my-opt keys_strict (SQL LIMIT ?) + setMaxRows(10)", opt.getValue("keys_strict"), 10, ctx.paramsFor(opt.getValue("keys_strict"), 0)),
                        Case("3 my-opt oldest_due_strict + setMaxRows(1)", opt.getValue("oldest_due_strict"), 1, ctx.paramsFor(opt.getValue("oldest_due_strict"), 0)),
                        Case("4 my-main keys_strict (SQL LIMIT ?), no setMaxRows", main.getValue("keys_strict"), 0, ctx.paramsFor(main.getValue("keys_strict"), 0)),
                    )
                    for ((i, cs) in cases.withIndex()) {
                        marker("${i + 1}-start")
                        val h0 = hand(); val t = System.nanoTime()
                        val r = Bench.exec(c, cs.q, cs.p, cs.mr)
                        val ms = (System.nanoTime() - t) / 1e6
                        val h1 = hand()
                        marker("${i + 1}-end")
                        sb.appendLine("case ${cs.name}: rows returned to the application = ${r.rows}, %.2f ms, session handler deltas: ".format(ms) + h1.keys.joinToString(" ") { "$it=${h1[it]!! - (h0[it] ?: 0)}" })
                    }
                    Thread.sleep(500)
                    sb.appendLine().appendLine("== mysql.general_log of the test connection (thread $tid), SHOW STATUS and general_log reads omitted ==")
                    adm.createStatement().use { st -> st.executeQuery("SELECT DATE_FORMAT(event_time,'%H:%i:%s.%f'), command_type, CONVERT(argument USING utf8mb4) FROM mysql.general_log WHERE thread_id = $tid ORDER BY event_time").use { rs ->
                        while (rs.next()) { val a = rs.getString(3) ?: ""; if (a.startsWith("SHOW SESSION STATUS") || a.startsWith("SELECT CONNECTION_ID")) continue
                            sb.appendLine("${rs.getString(1)}  ${rs.getString(2)}  ${a.take(700)}") } } }
                    val sel = adm.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM mysql.general_log WHERE thread_id = $tid AND CONVERT(argument USING utf8mb4) LIKE '%SQL_SELECT_LIMIT%'").use { rs -> rs.next(); rs.getInt(1) } }
                    sb.appendLine().appendLine("statements of the test connection mentioning SQL_SELECT_LIMIT: $sel")
                }
            } finally {
                adm.createStatement().use { it.execute("SET GLOBAL general_log = 'OFF'"); it.execute("TRUNCATE TABLE mysql.general_log"); it.execute("SET GLOBAL log_output = '$oldOut'") }
            }
        }
        val f = File(o.get("out")); f.parentFile?.mkdirs(); f.writeText(sb.toString()); println(sb)
    }
}

/** Dialect dispatch for the InnoDB helpers used by writecost / concurrency / churn (MariaDB -> [Maria], MySQL -> [MySql]). */
object Inno {
    fun settle(d: Dialect, c: Connection): String = if (d == Dialect.MYSQL) MySql.settleHistory(c) else Maria.settleHistory(c)
    fun hll(d: Dialect, c: Connection): Long = if (d == Dialect.MYSQL) MySql.historyListLength(c) else Maria.historyListLength(c)
    fun globals(d: Dialect, c: Connection): Map<String, Long> = if (d == Dialect.MYSQL) MySql.globals(c) else Maria.globals(c)
    fun delta(d: Dialect, a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> = if (d == Dialect.MYSQL) MySql.delta(a, b) else Maria.delta(a, b)
    fun tablespace(d: Dialect, c: Connection): Pair<Long, Long>? = if (d == Dialect.MYSQL) MySql.tablespaceBytes(c) else Maria.tablespaceBytes(c)
}
