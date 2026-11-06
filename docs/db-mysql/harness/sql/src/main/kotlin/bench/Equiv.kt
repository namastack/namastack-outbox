package bench

import java.io.File
import java.sql.Connection

/** Compares every read query and the effect of every write/delete between two variants on identical data. */
object Equiv {
    class Side(val name: String, val cm: Common, val c: Connection, val cat: Map<String, CatalogQuery>, val ctx: Ctx)

    private fun side(o: Opts, tag: String, schema: String): Side {
        val variant = o.get(tag)
        val args = mutableListOf("--dialect", o.get("dialect"), "--variant", variant, "--scenario", o.get("scenario", "S1"), "--schema", schema)
        o.opt("catalog-variant-$tag")?.let { args += listOf("--catalog-variant", it) }
        o.opt("worktree-$tag")?.let { args += listOf("--worktree", it) }
        o.opt("ddl-$tag")?.let { args += listOf("--ddl", it) }
        o.opt("queries-$tag")?.let { args += listOf("--queries", it) }
        o.opt("owned")?.let { args += listOf("--owned", it) }
        o.opt("batch")?.let { args += listOf("--batch", it) }
        o.opt("catalog")?.let { args += listOf("--catalog", it) }
        o.opt("results")?.let { args += listOf("--results", it) }
        val cm = Common(Opts(args))
        if (!o.has("no-prepare")) Bench.prepare(cm)
        val c = cm.dialect.connect(schema)
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        return Side(variant, cm, c, cat, Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, 30))
    }

    private fun run(s: Side, id: String, rep: Int = 0, patch: (MutableMap<String, Any?>) -> Unit = {}, unlimited: Boolean = false): Bench.ExecResult {
        val q = s.cat.getValue(id)
        val p = s.ctx.paramsFor(q, rep).toMutableMap(); patch(p)
        if (unlimited) p["limit"] = 1_000_000
        return Bench.exec(s.c, q, p, if (unlimited) 0 else Bench.maxRowsFor(q, s.ctx), collect = true)
    }

    /** Values of the ORDER BY column for the returned rows: taken from the result when it has the column, else looked up by id. */
    private fun orderVals(s: Side, r: Bench.ExecResult, idCol: Int, col: String): List<String?> {
        val ci = r.cols.indexOf(col)
        if (ci >= 0) return r.data!!.map { it[ci] }
        return r.data!!.map { row -> s.c.prepareStatement("SELECT $col FROM outbox_record WHERE id = ?").use { ps -> ps.setString(1, row[idCol]); ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } } }
    }

    private fun effect(s: Side, id: String): String {
        val q = s.cat.getValue(id)
        s.c.autoCommit = false
        try {
            val p = s.ctx.paramsFor(q, 0)
            val res = Bench.exec(s.c, q, p, Bench.maxRowsFor(q, s.ctx))
            val post = when (id) {
                "insert", "upd_complete", "upd_retry", "upd_fail", "delete_by_id" -> {
                    val r = Bench.exec(s.c, CatalogQuery("fp", "SELECT * FROM outbox_record WHERE id = :id", mapOf("kind" to "read"), File("fp")), mapOf("id" to p["id"]), 0, true)
                    r.data!!.joinToString("|") { it.joinToString(",") }
                }
                else -> Bench.exec(s.c, CatalogQuery("fp", "SELECT status, COUNT(*) FROM outbox_record GROUP BY status ORDER BY status", mapOf("kind" to "read"), File("fp")), emptyMap(), 0, true)
                    .data!!.joinToString("|") { it.joinToString(",") }
            }
            return "updated=${res.rows} post=$post"
        } finally { s.c.rollback(); s.c.autoCommit = true }
    }

    private fun statusCounts(s: Side, all: Boolean): Map<String, Long> {
        if (s.cat.containsKey("count_statuses")) {
            val id = if (all) "count_statuses_all" else "count_statuses"
            val r = run(s, id)
            val m = r.data!!.associate { it[0]!! to it[1]!!.toLong() }.toMutableMap()
            for (st in (if (all) listOf("NEW", "FAILED", "COMPLETED") else listOf("NEW", "FAILED"))) m.putIfAbsent(st, 0)
            return m
        }
        val m = mutableMapOf<String, Long>()
        m["NEW"] = run(s, "count_new").data!![0][0]!!.toLong(); m["FAILED"] = run(s, "count_failed").data!![0][0]!!.toLong()
        if (all) m["COMPLETED"] = run(s, "count_completed").data!![0][0]!!.toLong()
        return m
    }

    fun main(o: Opts) {
        val groups = Priority.groups(o)   // P1/P2 only (default HOT,WRITE,MON); rejected before anything is prepared
        val a = side(o, "a", "eq_a"); val b = side(o, "b", "eq_b")
        val rows = mutableListOf<Triple<String, String, String>>() // id, verdict, detail
        fun add(id: String, v: String, d: String = "") { rows += Triple(id, v, d); println("%-24s %-6s %s".format(id, v, d)) }
        val only = o.opt("only")?.split(",")?.toSet()   // restrict to these query ids (merged-form smoke)
        val ids = Catalog.ORDER.filter { id -> (only == null || id in only) && (id in a.cat || id in b.cat) && (a.cat[id] ?: b.cat[id])!!.group in groups }
        for (id in ids) {
            try {
                val adminOnly = a.cm.scenario.uppercase() in setOf("S1", "S3")
                if (id in setOf("find_pending_all", "find_failed_all") && !adminOnly) { add(id, "SKIP", "unbounded"); continue }
                if (id.startsWith("count_statuses")) {
                    val all = id.endsWith("_all"); val x = statusCounts(a, all); val y = statusCounts(b, all)
                    add(id, if (x == y) "PASS" else "FAIL", "a=$x b=$y"); continue
                }
                if (id !in a.cat || id !in b.cat) { add(id, "N/A", "only in ${if (id in a.cat) a.name else b.name}"); continue }
                val kind = a.cat.getValue(id).kind
                if (kind != "read") {
                    val x = effect(a, id); val y = effect(b, id)
                    add(id, if (x == y) "PASS" else "FAIL", if (x == y) x.take(150) else "a=$x | b=$y"); continue
                }
                if (id == "pages") { // pages are keyed by the strict key query result of each variant's own catalog
                    val keys = Bench.strictKeys(a.c, a.cat, a.ctx); a.ctx.pageKeys = keys; b.ctx.pageKeys = keys
                }
                val x = run(a, id); val y = run(b, id)
                val cx = x.cols; val cy = y.cols
                fun idx(r: Bench.ExecResult, n: String) = r.cols.indexOf(n)
                when {
                    id == "keys_strict" || id == "keys_nonstrict" -> {
                        val lx = x.data!!.map { it[0] }; val ly = y.data!!.map { it[0] }
                        if (lx == ly) add(id, "PASS", "${lx.size} keys, same order")
                        else {
                            val ux = run(a, id, unlimited = true).data!!.map { it[0] }; val uy = run(b, id, unlimited = true).data!!.map { it[0] }
                            if (ux.toSet() != uy.toSet() || ux.size != uy.size)
                                add(id, "FAIL", "limited a=${lx.size} b=${ly.size}; unlimited a=${ux.size} b=${uy.size}; only-a=${(ux.toSet() - uy.toSet()).take(3)} only-b=${(uy.toSet() - ux.toSet()).take(3)}")
                            else {
                                // The two queries order keys by MIN(created_at) of the key's due rows (main) or by (created_at, id) of its first due row (branch).
                                // WARN only when this is verified: both limited lists are sorted by that timestamp and the timestamp sequences are equal
                                // (so the lists can differ only by permutation inside created_at ties / which tied key falls on the limit boundary).
                                val q = a.cat.getValue(id); val par = a.ctx.paramsFor(q, 0)
                                fun ts(s: Side, keys: List<String?>): List<String> = keys.map { k ->
                                    s.c.prepareStatement("SELECT MIN(created_at) FROM outbox_record WHERE record_key = ? AND status = ? AND next_retry_at <= ?").use { ps ->
                                        ps.setString(1, k); ps.setString(2, par["status"] as String); ps.setTimestamp(3, par["now"] as java.sql.Timestamp)
                                        ps.executeQuery().use { rs -> rs.next(); rs.getString(1) ?: "?" } } }
                                val tx = ts(a, lx); val ty = ts(b, ly)
                                val sortedX = tx == tx.sorted(); val sortedY = ty == ty.sorted()
                                val boundary = tx.lastOrNull()
                                val tiedAtBoundary = if (boundary == null) 0 else a.c.prepareStatement("SELECT COUNT(*) FROM (SELECT record_key FROM outbox_record WHERE partition_no IN (${a.cm.owned.joinToString(",")}) AND status = ? AND next_retry_at <= ? GROUP BY record_key HAVING MIN(created_at) = ?) t").use { ps ->
                                    ps.setString(1, par["status"] as String); ps.setTimestamp(2, par["now"] as java.sql.Timestamp); ps.setString(3, boundary); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
                                if (sortedX && sortedY && tx == ty)
                                    add(id, "WARN", "limited lists differ (first difference at position ${lx.indices.firstOrNull { lx[it] != ly.getOrNull(it) } ?: -1}: a=${lx.firstOrNull { it !in ly.toSet() } ?: lx.getOrNull(lx.indices.firstOrNull { lx[it] != ly.getOrNull(it) } ?: 0)} b=${ly.firstOrNull { it !in lx.toSet() } ?: ly.getOrNull(lx.indices.firstOrNull { lx[it] != ly.getOrNull(it) } ?: 0)}, ${lx.toSet().minus(ly.toSet()).size} key(s) only in a, ${ly.toSet().minus(lx.toSet()).size} only in b) but full key sets are equal (${ux.size}); verified: both lists sorted by MIN(created_at) of the due rows and the created_at sequences are identical, so the difference is only created_at ties ($tiedAtBoundary keys tie with the last returned created_at)")
                                else add(id, "FAIL", "limited lists differ beyond ties: sorted a=$sortedX b=$sortedY, created_at sequences equal=${tx == ty} (a=${tx.take(5)} b=${ty.take(5)})")
                            }
                        }
                    }
                    id == "pages" -> {
                        // --pages-client-sort: a side whose pages SQL has no ORDER BY is compared after the client sort (group by key, created_at, id)
                        fun rowsOf(r: Bench.ExecResult, s: Side) = if (R3.applies(s.cat.getValue("pages"))) R3.sort(r) else r.data!!
                        fun grp(r: Bench.ExecResult, s: Side) = rowsOf(r, s).groupBy({ it[idx(r, "record_key")] }, { it[idx(r, "id")] })
                        val gx = grp(x, a); val gy = grp(y, b)
                        add(id, if (gx == gy) "PASS" else "FAIL", "${x.rows} vs ${y.rows} rows over ${gx.size} keys; per-key id order ${if (gx == gy) "equal" else "differs"}")
                    }
                    id.startsWith("find_") && id != "find_pending_all" && id != "find_failed_all" && id != "find_incomplete_by_key" || id.startsWith("ids_") -> {
                        val ox = if (id.contains("completed")) "completed_at" else "next_retry_at"   // ORDER BY column of the *_before queries
                        val ix = idx(x, "id").let { if (it < 0) 0 else it }; val iy = idx(y, "id").let { if (it < 0) 0 else it }
                        val sx = x.data!!.map { it[ix] }; val sy = y.data!!.map { it[iy] }
                        if (sx == sy) add(id, "PASS", "${sx.size} rows, same ids same order")
                        else if (sx.toSet() == sy.toSet()) {
                            // verified, not assumed: the order column must read identically in both lists (differences are only permutations of equal values)
                            if (orderVals(a, x, ix, ox) == orderVals(b, y, iy, ox)) add(id, "PASS", "same id set, order differs only inside equal $ox values (verified)")
                            else add(id, "FAIL", "same id set but the $ox sequences differ")
                        }
                        else {
                            // boundary ties: compare the order-column sequence instead
                            if (orderVals(a, x, ix, ox) == orderVals(b, y, iy, ox)) add(id, "WARN", "id sets differ only at the limit boundary (equal ${ox} values)")
                            else add(id, "FAIL", "a=${sx.size} rows b=${sy.size} rows")
                        }
                    }
                    else -> { // counts, MIN/oldest, ordered row lists
                        // a single all-NULL row (MIN over no rows) equals an empty result (ORDER BY ... LIMIT 1 form)
                        fun norm(r: Bench.ExecResult) = r.data!!.let { d -> if (d.size == 1 && d[0].all { it == null }) emptyList() else d }
                        val same = norm(x) == norm(y)
                        add(id, if (same) "PASS" else "FAIL", if (same) "${x.rows} row(s)${if (x.rows == 1) " = ${x.data!![0].take(2)}" else ""}" else "a=${x.data!!.take(3)} b=${y.data!!.take(3)}")
                    }
                }
            } catch (e: Exception) { add(id, "ERROR", e.message?.lines()?.first() ?: e.toString()); for (s in listOf(a, b)) try { s.c.rollback(); s.c.autoCommit = true } catch (_: Exception) {} }
        }
        val dir = File(a.cm.resultsRoot, "equiv/${a.cm.dialect.id}/${a.cm.scenario}").also { it.mkdirs() }
        val f = File(dir, "${a.name}-vs-${b.name}.md")
        f.writeText("# Equivalence ${a.name} vs ${b.name} (${a.cm.dialect.id}, ${a.cm.scenario})\n\n| query | verdict | detail |\n|---|---|---|\n" +
            rows.joinToString("\n") { "| ${it.first} | ${it.second} | ${it.third.replace("|", "/")} |" } + "\n")
        println("wrote $f")
        a.c.close(); b.c.close()
        if (rows.any { it.second == "FAIL" || it.second == "ERROR" }) System.exit(2)
    }
}
