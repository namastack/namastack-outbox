package bench

import java.io.File
import java.sql.Connection

/**
 * MariaDB-specific helpers: status counters, history list wait, buffer pool priming, server settings for env.txt,
 * limit-aware plan capture and the short plan summary (`plans/<id>.summary.txt`).
 */
object Maria {
    /** Note written to env.txt: history list length at the start of the first timed query and how long the wait took. */
    @Volatile var settleNote = "not measured"
    @Volatile var primeNote = "not primed"

    fun scalar(c: Connection, sql: String): String? = try {
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    } catch (e: Exception) { null }

    /** `SHOW [GLOBAL|SESSION] STATUS LIKE <like>` as name -> value (non-numeric values are skipped). */
    fun status(c: Connection, global: Boolean, like: String): Map<String, Long> {
        val m = linkedMapOf<String, Long>()
        c.createStatement().use { st -> st.executeQuery("SHOW ${if (global) "GLOBAL" else "SESSION"} STATUS LIKE '$like'").use { rs ->
            while (rs.next()) rs.getString(2)?.toLongOrNull()?.let { m[rs.getString(1)] = it } } }
        return m
    }

    fun historyListLength(c: Connection): Long = status(c, true, "Innodb_history_list_length")["Innodb_history_list_length"]
        ?: scalar(c, "SELECT count FROM information_schema.INNODB_METRICS WHERE name='trx_rseg_history_len'")?.toLongOrNull() ?: -1   // MySQL: no status variable

    /** Waits until `Innodb_history_list_length` < [below] (at most [maxS] s) and records the value in [settleNote]. */
    fun settleHistory(c: Connection, below: Long = 1000, maxS: Int = 120): String {
        val t0 = System.nanoTime(); var v = historyListLength(c)
        while (v >= below && (System.nanoTime() - t0) / 1e9 < maxS) { Thread.sleep(1000); v = historyListLength(c) }
        val s = (System.nanoTime() - t0) / 1e9
        settleNote = "$v (threshold < $below, waited %.1f s%s)".format(s, if (v >= below) ", TIMEOUT" else "")
        println("innodb_history_list_length at start: $settleNote")
        return settleNote
    }

    /**
     * Forces InnoDB to write all dirty pages (`innodb_max_dirty_pages_pct=0`), waits until the dirty page count stops changing
     * (at most [maxS] s) and restores the settings. Used around writecost phases so that `Innodb_pages_written` / `Innodb_data_written`
     * count the pages a phase dirtied (an idle server otherwise keeps them dirty for a long time). Returns the dirty page count left.
     */
    fun flushAll(c: Connection, maxS: Int = 120): Long {
        val oldPct = scalar(c, "SELECT @@global.innodb_max_dirty_pages_pct") ?: "90"; val oldLwm = scalar(c, "SELECT @@global.innodb_max_dirty_pages_pct_lwm") ?: "0"
        c.createStatement().use { st -> st.execute("SET GLOBAL innodb_max_dirty_pages_pct_lwm=0"); st.execute("SET GLOBAL innodb_max_dirty_pages_pct=0") }
        var last = -1L; var same = 0; val t0 = System.nanoTime(); var d = -1L
        try {
            while (same < 2 && (System.nanoTime() - t0) / 1e9 < maxS) {
                Thread.sleep(1000); d = status(c, true, "Innodb_buffer_pool_pages_dirty")["Innodb_buffer_pool_pages_dirty"] ?: -1
                if (d <= 5) break
                if (d == last) same++ else { same = 0; last = d }
            }
        } finally { c.createStatement().use { st -> st.execute("SET GLOBAL innodb_max_dirty_pages_pct=$oldPct"); st.execute("SET GLOBAL innodb_max_dirty_pages_pct_lwm=$oldLwm") } }
        return d
    }

    /** Index sizes in bytes from the persistent statistics (index name -> bytes), after `ANALYZE TABLE` when [analyze]. */
    fun indexSizes(c: Connection, analyze: Boolean): Map<String, Long> {
        if (analyze) c.createStatement().use { st -> st.executeQuery("ANALYZE TABLE outbox_record").use { while (it.next()) {} } }
        val ps = (scalar(c, "SELECT @@innodb_page_size") ?: "16384").toLong()
        val m = linkedMapOf<String, Long>()
        c.createStatement().use { st -> st.executeQuery("SELECT index_name, stat_value FROM mysql.innodb_index_stats WHERE database_name=DATABASE() AND table_name='outbox_record' AND stat_name='size' ORDER BY 1").use { rs ->
            while (rs.next()) m[rs.getString(1)] = rs.getLong(2) * ps } }
        return m
    }

    /** (file_size, allocated_size) of the table's tablespace file from information_schema, or null. */
    fun tablespaceBytes(c: Connection): Pair<Long, Long>? = try {
        c.createStatement().use { st -> st.executeQuery("SELECT file_size, allocated_size FROM information_schema.INNODB_SYS_TABLESPACES WHERE name = CONCAT(DATABASE(), '/outbox_record')").use { rs ->
            if (rs.next()) rs.getLong(1) to rs.getLong(2) else null } }
    } catch (e: Exception) { null }

    /**
     * Buffer pool priming, the same for every variant: scans each index of `outbox_record` (smallest first) with
     * `SELECT COUNT(*) ... FORCE INDEX` until the cumulative index size reaches 80 % of `innodb_buffer_pool_size`.
     */
    fun prime(c: Connection): String {
        val pool = (scalar(c, "SELECT @@innodb_buffer_pool_size") ?: "0").toLong()
        val sizes = indexSizes(c, false).entries.sortedBy { it.value }
        var sum = 0L; val done = mutableListOf<String>(); val skipped = mutableListOf<String>()
        for ((name, bytes) in sizes) {
            if (sum + bytes > pool * 0.8) { skipped += name; continue }
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM outbox_record FORCE INDEX (`$name`)").use { it.next() } }
            sum += bytes; done += name
        }
        val note = "primed ${done.size} index scans (${sum / 1048576} MiB of ${pool / 1048576} MiB pool): $done; skipped (too large): $skipped"
        println(note); return note
    }

    /** Server settings and run-schema facts for env.txt. */
    fun envBlock(c: Connection): String {
        val sb = StringBuilder()
        sb.appendLine("--- mariadb settings ---")
        for (v in listOf("version", "innodb_stats_persistent", "innodb_stats_persistent_sample_pages", "innodb_stats_auto_recalc", "use_stat_tables",
            "optimizer_use_condition_selectivity", "eq_range_index_dive_limit", "optimizer_switch", "innodb_buffer_pool_size",
            "innodb_flush_log_at_trx_commit", "innodb_flush_method", "log_bin", "innodb_log_file_size", "innodb_page_size",
            "explicit_defaults_for_timestamp", "character_set_server", "collation_server", "sql_mode", "transaction_isolation", "time_zone"))
            sb.appendLine("$v: ${scalar(c, "SELECT @@$v") ?: scalar(c, "SELECT @@global.$v") ?: "n/a"}")
        sb.appendLine("session time_zone/sql_select_limit: ${scalar(c, "SELECT CONCAT(@@session.time_zone, ' / ', @@session.sql_select_limit)")}")
        sb.appendLine("innodb_history_list_length_at_start: $settleNote")
        sb.appendLine("buffer_pool_priming: $primeNote")
        sb.appendLine("--- run schema column charset/collation (information_schema.COLUMNS) ---")
        try {
            c.createStatement().use { st -> st.executeQuery("SELECT column_name, column_type, character_set_name, collation_name FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND table_name='outbox_record' AND column_name IN ('id','record_key') ORDER BY ordinal_position").use { rs ->
                while (rs.next()) sb.appendLine("column ${rs.getString(1)}: ${rs.getString(2)} charset=${rs.getString(3)} collation=${rs.getString(4)}") } }
            c.createStatement().use { st -> st.executeQuery("SELECT table_collation FROM information_schema.TABLES WHERE table_schema=DATABASE() AND table_name='outbox_record'").use { rs -> if (rs.next()) sb.appendLine("table_collation: ${rs.getString(1)}") } }
            if (scalar(c, "SELECT @@use_stat_tables") != null) sb.appendLine("engine_independent_stats rows (mysql.table_stats/column_stats/index_stats for this schema): " +
                listOf("table_stats", "column_stats", "index_stats").joinToString(" / ") { t -> scalar(c, "SELECT COUNT(*) FROM mysql.$t WHERE db_name=DATABASE() AND table_name='outbox_record'") ?: "n/a" })
        } catch (e: Exception) { sb.appendLine("column info failed: ${e.message?.lines()?.first()}") }
        return sb.toString()
    }

    // ---- plans -----------------------------------------------------------------------------------------------------

    /** The limit the MariaDB driver applies for `setMaxRows(n)` is `SET STATEMENT SQL_SELECT_LIMIT=n FOR <sql>`; plans use the same prefix. */
    fun limitPrefix(maxRows: Int) = if (maxRows > 0) "SET STATEMENT SQL_SELECT_LIMIT=$maxRows FOR " else ""

    fun explainText(c: Connection, sql: String): String =
        c.createStatement().use { st -> st.executeQuery(sql).use { rs ->
            val sb = StringBuilder(); while (rs.next()) sb.appendLine((1..rs.metaData.columnCount).joinToString("\t") { rs.getString(it) ?: "" }); sb.toString() } }

    /** Writes `<id>.json` (+ `<id>.summary.txt`) for the literalized statement, under the same row limit as the driver applies. */
    fun capture(c: Connection, q: CatalogQuery, lit: String, maxRows: Int, rows: Int, dir: File, executing: Boolean) {
        val head = if (executing) "ANALYZE FORMAT=JSON " else "EXPLAIN FORMAT=JSON "
        val json = explainText(c, limitPrefix(maxRows) + head + lit)
        File(dir, "${q.id}.json").writeText(json)
        File(dir, "${q.id}.summary.txt").writeText(summary(q.id, json, executing, maxRows, rows))
    }

    // ---- minimal JSON reader (maps, lists, strings, numbers, booleans, null) ------------------------------------------

    private class J(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val ch = s[i]) {
                '{' -> { i++; val m = linkedMapOf<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
                '[' -> { i++; val l = mutableListOf<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble().also { check(i > st) { "bad json at $st: $ch" } } }
            }
        }
        fun str(): String {
            check(s[i] == '"'); i++; val sb = StringBuilder()
            while (s[i] != '"') { if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } } else sb.append(s[i]); i++ }
            i++; return sb.toString()
        }
    }

    private fun num(x: Any?) = (x as? Double)
    private fun fmt(x: Double?) = if (x == null) "-" else if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else "%.3f".format(x)

    /** Short, quotable summary of a MariaDB JSON plan: per table access type/key/key parts/rows, filesort, temporary table, strategies. */
    fun summary(id: String, json: String, executing: Boolean, maxRows: Int, rowsReturned: Int): String {
        val sb = StringBuilder()
        sb.appendLine("id: $id   mode: ${if (executing) "ANALYZE (executed)" else "EXPLAIN (not executed)"}   sql_select_limit: ${if (maxRows > 0) maxRows else "none"}   rows_returned: ${if (rowsReturned >= 0) rowsReturned else "n/a"}")
        val root = try { J(json.trim()).value() as Map<*, *> } catch (e: Exception) { sb.appendLine("summary failed: parse error ${e.message}"); return sb.toString() }
        val qb = root["query_block"] as? Map<*, *>
        sb.appendLine("r_total_time_ms: ${fmt(num(qb?.get("r_total_time_ms")))}   cost: ${fmt(num(qb?.get("cost")))}")
        var examined = 0.0; var filesort = false; var temp = false; var dups = false; val strategies = mutableSetOf<String>()
        val known = setOf("table_name", "access_type", "possible_keys", "key", "key_length", "used_key_parts", "ref", "r_loops", "rows", "r_rows", "r_table_time_ms", "r_other_time_ms",
            "filtered", "r_filtered", "index_condition", "attached_condition", "using_index", "rowid_filter", "r_total_filtered", "r_index_rows", "r_icp_filtered", "r_engine_stats", "cost",
            "r_unpack_time_ms", "r_buffer_size", "r_filling_time_ms", "partitions", "r_index_rows", "r_total_time_ms", "mrr_type", "clustered_index", "index_condition_bka", "pushed_index_condition")
        fun walk(n: Any?, sel: String) {
            when (n) {
                is Map<*, *> -> {
                    var cur = sel
                    if (n.containsKey("select_id")) cur = "select#" + fmt(num(n["select_id"]))
                    for ((k, v) in n) {
                        when (k) {
                            "filesort" -> filesort = true
                            "temporary_table" -> temp = true
                            "duplicates_removal" -> dups = true
                            "using_filesort" -> if (v == true) filesort = true
                            "materialized", "materialized_from_subquery" -> strategies += "materialization"
                            "subqueries" -> strategies += "dependent-subquery"
                            "subquery_cache" -> strategies += "subquery-cache"
                            "block-nl-join" -> strategies += "block-nl-join"
                            "unique_row_not_exists", "not_exists" -> strategies += "not-exists (anti-join)"
                            "first_match", "loosescan", "firstmatch", "table_loosescan" -> strategies += k.toString()
                        }
                        if (k == "table" && v is Map<*, *> && v.containsKey("table_name")) {
                            val t = v
                            val loops = num(t["r_loops"]) ?: 1.0; val rr = num(t["r_rows"]); val est = num(t["rows"])
                            val ri = num(t["r_index_rows"]); val ex = (ri ?: rr)?.let { it * loops }   // rows read from the index/table: r_index_rows (before index-condition filtering) when reported
                            if (ex != null) examined += ex
                            val parts = (t["used_key_parts"] as? List<*>)?.joinToString(",") ?: "-"
                            val extras = t.entries.filter { (kk, vv) -> kk !in known && vv == true }.joinToString(",") { it.key.toString() }
                            sb.appendLine("$cur table=${t["table_name"]} access=${t["access_type"] ?: "-"} key=${t["key"] ?: "-"} key_parts=[$parts] key_len=${t["key_length"] ?: "-"} " +
                                "loops=${fmt(loops)} rows_est=${fmt(est)} r_rows=${fmt(rr)} r_index_rows=${fmt(ri)} examined=${fmt(ex)} filtered_est=${fmt(num(t["filtered"]))} r_filtered=${fmt(num(t["r_filtered"]))} " +
                                "index_condition=${if (t.containsKey("index_condition")) "yes" else "no"} attached_condition=${if (t.containsKey("attached_condition")) "yes" else "no"} " +
                                "using_index=${if (t["using_index"] == true) "yes" else "no"} rowid_filter=${if (t.containsKey("rowid_filter")) "yes" else "no"}" +
                                (if (extras.isNotEmpty()) " extra=[$extras]" else ""))
                        }
                        walk(v, cur)
                    }
                }
                is List<*> -> n.forEach { walk(it, sel) }
            }
        }
        walk(qb, "select#1")
        sb.appendLine("filesort: ${if (filesort) "yes" else "no"}   temporary_table: ${if (temp) "yes" else "no"}   duplicates_removal: ${if (dups) "yes" else "no"}   strategies: ${if (strategies.isEmpty()) "-" else strategies.joinToString(",")}")
        sb.appendLine("rows_examined_total: ${if (executing) fmt(examined) else "n/a (not executed)"}   rows_returned: ${if (rowsReturned >= 0) rowsReturned else "n/a"}")
        return sb.toString()
    }

    // ---- Handler_read_* per query -----------------------------------------------------------------------------------

    val HANDLERS = listOf("Handler_read_key", "Handler_read_next", "Handler_read_prev", "Handler_read_first", "Handler_read_rnd", "Handler_read_rnd_next", "Handler_read_last")
    const val HANDLER_HEADER = "query_id,Handler_read_key,Handler_read_next,Handler_read_prev,Handler_read_first,Handler_read_rnd,Handler_read_rnd_next,Handler_read_last,bp_read_requests,bp_reads"

    /**
     * Session `Handler_read%` deltas of one extra execution (outside the timed section). `SHOW STATUS` itself changes the session
     * counters slightly, so the cost of two consecutive `SHOW STATUS` calls is measured and subtracted. The same is done for the
     * global `Innodb_buffer_pool_read_requests` / `Innodb_buffer_pool_reads` (a lane has its own server, so they belong to this query).
     */
    fun handlerRow(c: Connection, q: CatalogQuery, p: Map<String, Any?>, maxRows: Int): String {
        fun snap(): Pair<Map<String, Long>, Map<String, Long>> = status(c, false, "Handler_read%") to status(c, true, "Innodb_buffer_pool_read%")
        val write = q.kind != "read"
        c.autoCommit = !write
        try {
            val s0 = snap(); val s1 = snap()
            Bench.exec(c, q, p, maxRows)
            val s2 = snap()
            fun d(k: String, g: Boolean) = ((if (g) s2.second else s2.first)[k] ?: 0) - ((if (g) s1.second else s1.first)[k] ?: 0) - (((if (g) s1.second else s1.first)[k] ?: 0) - ((if (g) s0.second else s0.first)[k] ?: 0))
            return "${q.id}," + HANDLERS.joinToString(",") { d(it, false).toString() } + ",${d("Innodb_buffer_pool_read_requests", true)},${d("Innodb_buffer_pool_reads", true)}"
        } finally { if (write) try { c.rollback() } catch (_: Exception) {}; c.autoCommit = true }
    }

    /** Global InnoDB counters used by writecost / concurrency / churn. Missing counters are reported as 0 deltas. */
    val GLOBALS = listOf("Innodb_os_log_written", "Innodb_pages_written", "Innodb_data_written", "Innodb_buffer_pool_read_requests", "Innodb_buffer_pool_reads",
        "Innodb_row_lock_waits", "Innodb_row_lock_time", "Innodb_deadlocks", "Innodb_buffer_pool_pages_dirty")

    fun globals(c: Connection): Map<String, Long> {
        val m = linkedMapOf<String, Long>()
        for (g in GLOBALS) m.putAll(status(c, true, g))
        return m
    }

    fun delta(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> = GLOBALS.associateWith { (b[it] ?: 0) - (a[it] ?: 0) }
}
