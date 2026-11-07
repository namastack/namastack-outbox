package bench

import java.io.File

/**
 * `plantime` (confirm stage, isolation test): median/p95 of `EXPLAIN FORMAT=JSON <literal statement>` through the JDBC path of the bench (same row limit prefix as the plan capture),
 * and the optimizer trace of one EXPLAIN per query (`trace/<id>.json`). Options: `--only id1,id2`, `--warmup` (default 3), `--reps` (default 20), `--owned`, `--batch` as for bench.
 * Output: `<results>/<variant>/mariadb/<label>/plantime.csv` and `trace/`. Nothing is executed or modified (EXPLAIN only).
 */
object Plantime {
    fun run(cm: Common, o: Opts) {
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        val only = o.opt("only")?.split(",") ?: listOf("keys_strict", "keys_nonstrict", "oldest_created", "oldest_due_strict", "oldest_due_nonstrict")
        val dir = cm.outDir; File(dir, "trace").mkdirs()
        val csv = StringBuilder("query_id,median_ms,p95_ms,min_ms,reps\n")
        cm.dialect.connect(cm.schema).use { c ->
            val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, 30)
            for (id in only) {
                val q = cat[id] ?: continue
                val (sql, values) = NamedSql.expand(q.sql, ctx.paramsFor(q, 0))
                val lit = Maria.limitPrefix(Bench.maxRowsFor(q, ctx)) + "EXPLAIN FORMAT=JSON " + NamedSql.literalize(cm.dialect, sql, values)
                val ts = mutableListOf<Double>()
                for (i in 0 until cm.warmup.coerceAtMost(3) + o.int("reps", 20)) {
                    val t = System.nanoTime()
                    c.createStatement().use { st -> st.executeQuery(lit).use { rs -> while (rs.next()) rs.getString(1) } }
                    val ms = (System.nanoTime() - t) / 1e6
                    if (i >= 3) ts += ms
                }
                val s = ts.sorted()
                csv.appendLine("$id,%.3f,%.3f,%.3f,${s.size}".format(Bench.percentile(s, .5), Bench.percentile(s, .95), s.first()))
                println("%-24s explain median %.3f ms p95 %.3f".format(id, Bench.percentile(s, .5), Bench.percentile(s, .95)))
                c.createStatement().use { st ->
                    st.execute("SET optimizer_trace='enabled=on'")
                    st.executeQuery(lit).use { rs -> while (rs.next()) rs.getString(1) }
                    st.executeQuery("SELECT TRACE FROM information_schema.OPTIMIZER_TRACE").use { rs -> if (rs.next()) File(dir, "trace/$id.json").writeText(rs.getString(1) ?: "") }
                    st.execute("SET optimizer_trace='enabled=off'")
                }
            }
        }
        File(dir, "plantime.csv").writeText(csv.toString())
    }
}
