package bench

import java.io.File
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object WriteCost {
    fun run(cm: Common, o: Opts) {
        Nonce.value = System.nanoTime().toString()
        val n = o.int("n", 5000); val threads = o.int("threads", 4)
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        val ins = cat.getValue("insert"); val upd = cat.getValue("upd_complete")
        val out = StringBuilder("metric,value\n")
        val dir = cm.outDir
        cm.dialect.connect(cm.schema).use { c0 ->
            val ctx = Ctx(cm.dialect, c0, cm.scenario, cm.batch, cm.owned, n + 10)
            val pg = cm.dialect == Dialect.POSTGRES
            fun scalar(sql: String): String = c0.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }
            fun idxReport(tag: String) {
                if (!pg) return
                c0.createStatement().use { st -> st.executeQuery("SELECT indexrelid::regclass::text, pg_relation_size(indexrelid) FROM pg_index WHERE indrelid='outbox_record'::regclass ORDER BY 1").use { rs ->
                    var n = 0; while (rs.next()) { n++; out.appendLine("index_bytes_$tag.${rs.getString(1)},${rs.getLong(2)}") }; if (tag == "before") out.appendLine("index_count,$n") } }
                out.appendLine("table_bytes_$tag,${scalar("SELECT pg_table_size('outbox_record')")}")
                out.appendLine("indexes_total_bytes_$tag,${scalar("SELECT pg_indexes_size('outbox_record')")}")
            }
            idxReport("before")
            // WAL bytes (PostgreSQL only): pg_current_wal_lsn() difference over each phase, after a CHECKPOINT so every phase starts with full-page-write state
            fun <T> walPhase(label: String, writes: () -> Int): Int {
                if (!pg) return writes()
                try { c0.createStatement().use { it.execute("CHECKPOINT") } } catch (_: Exception) {}
                val lsn0 = scalar("SELECT pg_current_wal_lsn()")
                val w = writes()
                val bytes = scalar("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '$lsn0')").toDouble()
                out.appendLine("$label.wal_bytes_total,%.0f".format(bytes)); out.appendLine("$label.wal_bytes_per_write,%.1f".format(bytes / w))
                return w
            }
            val before = cm.dialect.sizeReport(c0)
            fun stats(label: String, lat: List<Double>, wallMs: Double) {
                val s = lat.sorted()
                out.appendLine("$label.count,${s.size}"); out.appendLine("$label.p50_ms,%.3f".format(Bench.percentile(s, .5)))
                out.appendLine("$label.p95_ms,%.3f".format(Bench.percentile(s, .95))); out.appendLine("$label.throughput_per_s,%.1f".format(s.size / (wallMs / 1000)))
            }
            // committed single-row inserts, 1 thread
            fun insertRun(label: String, prefix: String, nThreads: Int, perThread: Int) {
                val lats = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val t0 = System.nanoTime()
                (0 until nThreads).map { ti -> thread {
                    cm.dialect.connect(cm.schema).use { c ->
                        c.autoCommit = true
                        for (i in 0 until perThread) {
                            val p = hashMapOf<String, Any?>(); ctx.insertParams(p, ti * 10_000_000 + i, T0_MICROS, prefix)
                            val t = System.nanoTime(); Bench.exec(c, ins, p, 0); lats += (System.nanoTime() - t) / 1e6
                        }
                    }
                } }.forEach { it.join() }
                stats(label, lats.toList(), (System.nanoTime() - t0) / 1e6)
            }
            // warm up
            insertRun("insert_warmup", "order-wcw-", 1, 200)
            walPhase<Unit>("insert_1thread") { insertRun("insert_1thread", "order-wc1-", 1, n); n }
            walPhase<Unit>("insert_${threads}threads") { insertRun("insert_${threads}threads", "order-wc4-", threads, n / threads); n / threads * threads }
            // committed status updates (complete) on due records
            val ids = ctx.dueIds.take(n)
            val lats = mutableListOf<Double>(); c0.autoCommit = true
            var t0 = System.nanoTime()
            walPhase<Unit>("upd_complete_committed") {
                t0 = System.nanoTime()
                for ((i, id) in ids.withIndex()) {
                    val p = ctx.paramsFor(upd, i).toMutableMap(); p["id"] = id
                    val t = System.nanoTime(); Bench.exec(c0, upd, p, 0); lats += (System.nanoTime() - t) / 1e6
                }
                ids.size
            }
            stats("upd_complete_committed", lats, (System.nanoTime() - t0) / 1e6)
            idxReport("after")
            File(dir, "writecost.csv").writeText(out.toString())
            File(dir, "sizes-before-write.txt").writeText(before)
            File(dir, "sizes-after-write.txt").writeText(cm.dialect.sizeReport(c0))
            println(out)
        }
    }
}

object Analyze {
    /** `analyze --n 5`: times `ANALYZE outbox_record` n times (PostgreSQL), writes analyze.txt (E14: with/without extended statistics). */
    fun run(cm: Common, o: Opts) {
        require(cm.dialect == Dialect.POSTGRES) { "analyze: PostgreSQL only" }
        val n = o.int("n", 5); val ms = mutableListOf<Double>()
        cm.dialect.connect(cm.schema).use { c ->
            c.autoCommit = true
            repeat(n) { c.createStatement().use { st -> val t = System.nanoTime(); st.execute("ANALYZE outbox_record"); ms += (System.nanoTime() - t) / 1e6 } }
            val stx = c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM pg_statistic_ext WHERE stxrelid='outbox_record'::regclass").use { rs -> rs.next(); rs.getInt(1) } }
            val s = ms.sorted()
            val txt = "analyze_ms: " + ms.joinToString(" ") { "%.1f".format(it) } + "\nmedian_ms: %.1f\nextended_statistics_objects: $stx\n".format(Bench.percentile(s, .5))
            File(cm.outDir, "analyze.txt").writeText(txt); println(txt)
        }
    }
}

object Concurrency {
    class Lat { val v = java.util.Collections.synchronizedList(mutableListOf<Double>()); fun add(ms: Double) { v += ms }
        fun summary(): String { val s = v.toList().sorted(); return if (s.isEmpty()) "n=0" else "n=%d p50=%.2f p95=%.2f".format(s.size, Bench.percentile(s, .5), Bench.percentile(s, .95)) } }

    fun run(cm: Common, o: Opts) {
        Nonce.value = System.nanoTime().toString()
        val pollers = o.int("pollers", 1); val seconds = o.int("seconds", 60); val rate = o.int("rate", 500)
        val prodThreads = o.int("producers", if (rate == 0) 4 else 1); val idle = o.int("idle-ms", 100); val tag = o.opt("tag") ?: "p$pollers-r$rate"
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        val keysQ = cat.getValue("keys_strict"); val pagesQ = cat.getValue("pages"); val updQ = cat.getValue("upd_complete"); val insQ = cat.getValue("insert")
        val stop = AtomicBoolean(false); val processed = AtomicLong(); val inserted = AtomicLong(); val errors = AtomicLong()
        val latKeys = Lat(); val latPages = Lat(); val latPagesDb = Lat(); val latUpd = Lat(); val latIns = Lat()
        val pg = cm.dialect == Dialect.POSTGRES
        val capture = pg && o.has("capture-plans")   // auto_explain per poller session + final plan capture (stage 0 of round 3)
        val pollerInfo = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sinceInstant = java.time.Instant.now().toString()
        val start = System.nanoTime()
        fun virtualNow(): Long = T0_MICROS + (System.nanoTime() - start) / 1000
        val ctx0 = cm.dialect.connect(cm.schema).use { Ctx(cm.dialect, it, cm.scenario, cm.batch, cm.owned, 10) }
        val workers = mutableListOf<Thread>()
        for (pi in 0 until pollers) {
            val owned = if (pollers == 1) (0 until 256).toList() else (0 until 256).filter { it % pollers == pi }
            workers += thread {
                cm.dialect.connect(cm.schema).use { c ->
                    c.autoCommit = true
                    val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, owned, 10)
                    var lastPP: Map<String, Any?>? = null
                    if (capture) c.createStatement().use { st ->   // slow statements (>= 5 ms) of this session are logged with their plan (no ANALYZE overhead)
                        for (sql in listOf("LOAD 'auto_explain'", "SET auto_explain.log_min_duration = '5ms'", "SET auto_explain.log_analyze = off", "SET auto_explain.log_nested_statements = off")) st.execute(sql) }
                    while (!stop.get()) try {
                        val nowTs = tsOfMicros(virtualNow())
                        val pk = ctx.paramsFor(keysQ, 0).toMutableMap(); pk["now"] = nowTs; pk["partitions"] = owned
                        var t = System.nanoTime(); val keys = Bench.exec(c, keysQ, pk, Bench.maxRowsFor(keysQ, ctx), collect = true).data!!.map { it[0]!! }
                        latKeys.add((System.nanoTime() - t) / 1e6)
                        if (keys.isEmpty()) { Thread.sleep(idle.toLong()); continue }
                        val pp = ctx.paramsFor(pagesQ, 0).toMutableMap(); pp["recordKeys"] = keys
                        t = System.nanoTime(); val res = Bench.exec(c, pagesQ, pp, 0, collect = true)
                        latPagesDb.add((System.nanoTime() - t) / 1e6)
                        // --pages-client-sort: group by key + sort by (created_at, id) in the JVM, inside the timed section; processing then sees per-key sorted rows
                        val pageRows = if (R3.applies(pagesQ)) R3.sort(res) else res.data!!
                        latPages.add((System.nanoTime() - t) / 1e6); lastPP = pp
                        val iId = res.cols.indexOf("id"); val iKey = res.cols.indexOf("record_key"); val iNext = res.cols.indexOf("next_retry_at")
                        val blocked = HashSet<String>()
                        for (r in pageRows) {
                            val key = r[iKey]!!; if (key in blocked) continue
                            // strict per-key ordering: only a due record may be processed, later records of the key wait
                            val due = r[iNext]!!.let { s -> java.sql.Timestamp.valueOf(s.substring(0, 19).replace('T', ' ')).time <= nowTs.time + 1000 }
                            if (!due) { blocked += key; continue }
                            val up = hashMapOf<String, Any?>("id" to r[iId], "status" to "COMPLETED", "completedAt" to nowTs, "failureCount" to 0, "failureReason" to Null(java.sql.Types.VARCHAR), "nextRetryAt" to nowTs)
                            t = System.nanoTime(); Bench.exec(c, updQ, up, 0); latUpd.add((System.nanoTime() - t) / 1e6); processed.incrementAndGet()
                        }
                    } catch (e: Exception) { errors.incrementAndGet(); if (errors.get() < 3) e.printStackTrace() }
                    if (pg) try { // end of run: plan-cache counters of every `pages` statement of this session (text differs per IN-list length) and the plan that ran
                        c.createStatement().use { st -> st.executeQuery("SELECT count(*), coalesce(sum(custom_plans),0), coalesce(sum(generic_plans),0) FROM pg_prepared_statements WHERE statement LIKE '/* pages */%'").use { rs ->
                            rs.next(); pollerInfo += "poller $pi: pages server-prepared statements=${rs.getLong(1)} custom_plans=${rs.getLong(2)} generic_plans=${rs.getLong(3)} (executions before the 5th use an unnamed statement and are not counted)" } }
                        val pp = lastPP
                        if (capture && pp != null) {
                            c.createStatement().use { st -> for (sql in listOf("SET auto_explain.log_min_duration = 0", "SET auto_explain.log_analyze = on", "SET auto_explain.log_buffers = on")) st.execute(sql) }
                            Bench.exec(c, pagesQ, pp, 0, collect = true)   // same statement path as in the loop: logs the plan that runs now (custom or reused generic)
                            c.createStatement().use { st -> st.execute("SET auto_explain.log_min_duration = -1") }
                            val pd = File(cm.outDir, "plans-$tag-p$pi").also { it.mkdirs() }
                            Bench.plan(cm.dialect, c, pagesQ, pp, 0, pd)?.let { pollerInfo += "poller $pi: EXPLAIN ANALYZE (custom plan, last key set, ${(pp["recordKeys"] as List<*>).size} keys) planning %.3f ms, execution %.3f ms (plans-$tag-p$pi/pages.txt)".format(it.first, it.second) }
                        }
                    } catch (e: Exception) { pollerInfo += "poller $pi: end-of-run capture failed: $e" }
                }
            }
        }
        for (pi in 0 until prodThreads) workers += thread {
            cm.dialect.connect(cm.schema).use { c ->
                c.autoCommit = true
                val rnd = java.util.Random(7L + pi); var seq = 0
                val perThreadRate = if (rate == 0) 0.0 else rate.toDouble() / prodThreads
                val begin = System.nanoTime()
                while (!stop.get()) try {
                    if (perThreadRate > 0) { val due = begin + (seq / perThreadRate * 1e9).toLong(); val w = due - System.nanoTime(); if (w > 0) Thread.sleep(w / 1_000_000, (w % 1_000_000).toInt()) }
                    val p = hashMapOf<String, Any?>(); ctx0.insertParams(p, pi * 100_000_000 + seq++, virtualNow(), "order-cc-${rnd.nextInt(100_000)}-")
                    val t = System.nanoTime(); Bench.exec(c, insQ, p, 0); latIns.add((System.nanoTime() - t) / 1e6); inserted.incrementAndGet()
                } catch (e: Exception) { errors.incrementAndGet(); if (errors.get() < 3) e.printStackTrace() }
            }
        }
        Thread.sleep(seconds * 1000L); stop.set(true); workers.forEach { it.join() }
        val secs = (System.nanoTime() - start) / 1e9
        if (capture) try { // auto_explain output of the run from the container log -> concurrency-<tag>.plans.txt
            val pr = ProcessBuilder("docker", "logs", "--since", sinceInstant, cm.dialect.container!!).redirectErrorStream(true).start()
            val log = pr.inputStream.bufferedReader().readText(); pr.waitFor()
            val entries = mutableListOf<StringBuilder>(); val head = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+ ")
            for (l in log.lines()) if (head.containsMatchIn(l)) entries += StringBuilder(l) else entries.lastOrNull()?.append('\n')?.append(l)
            val pagesPlans = entries.map { it.toString() }.filter { "plan:" in it && "/* pages */" in it }
            val fin = pagesPlans.filter { "actual time=" in it }; val slow = pagesPlans.filter { "actual time=" !in it }
            File(cm.outDir, "concurrency-$tag.plans.txt").writeText("pages statements >= 5 ms logged during the run: ${slow.size}\nfinal captures (one per poller, with actuals): ${fin.size}\n\n== first 3 slow pages plans ==\n" +
                slow.take(3).joinToString("\n\n") + "\n\n== final captures ==\n" + fin.joinToString("\n\n") + "\n")
            pollerInfo += "auto_explain: ${slow.size} pages plans >= 5 ms logged, ${fin.size} final captures (concurrency-$tag.plans.txt)"
        } catch (e: Exception) { pollerInfo += "auto_explain log read failed: $e" }
        val txt = """tag: $tag  pollers=$pollers producers=$prodThreads rate=${if (rate == 0) "unthrottled" else rate} seconds=%.1f
processed_per_s: %.1f   (total ${processed.get()})
inserted_per_s: %.1f   (total ${inserted.get()})
errors: ${errors.get()}
keys_query_ms: ${latKeys.summary()}
pages_query_ms: ${latPages.summary()}
pages_db_only_ms: ${latPagesDb.summary()}
client_sort: ${R3.pagesClientSort && R3.applies(pagesQ)}  session_init: ${Dialect.pgSessionSql}
${pollerInfo.sorted().joinToString("\n")}
update_ms: ${latUpd.summary()}
insert_ms: ${latIns.summary()}
""".format(secs, processed.get() / secs, inserted.get() / secs)
        File(cm.outDir, "concurrency-$tag.txt").writeText(txt); println(txt)
    }
}

object Churn {
    fun run(cm: Common, o: Opts) {
        Nonce.value = System.nanoTime().toString()
        val n = o.int("inserts", 500_000); val chunk = 1000
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        val ins = cat.getValue("insert"); val upd = cat.getValue("upd_complete"); val del = cat.getValue("delete_by_id")
        val idsQ = cat.getValue("ids_completed_before"); val delIds = cat.getValue("delete_by_ids")
        val rep = StringBuilder()
        cm.dialect.connect(cm.schema).use { c ->
            val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, 10)
            rep.appendLine("== before ==").append(cm.dialect.sizeReport(c))
            val t0 = System.nanoTime(); val rnd = java.util.Random(3)
            c.autoCommit = false
            var seq = 0
            fun batchExec(q: CatalogQuery, ps: List<Map<String, Any?>>) {
                val (sql, _) = NamedSql.expand(q.sql, ps.first())
                c.prepareStatement(sql).use { st -> for (p in ps) { NamedSql.bind(st, NamedSql.expand(q.sql, p).second); st.addBatch() }; st.executeBatch() }
            }
            while (seq < n) {
                val m = minOf(chunk, n - seq)
                val recs = (0 until m).map { i -> hashMapOf<String, Any?>().also { ctx.insertParams(it, 50_000_000 + seq + i, T0_MICROS + (seq + i) * 1000L, "order-churn-${(seq + i) % 100000}-") } }
                batchExec(ins, recs); c.commit()
                // processing: 30% delete-on-complete, 2% FAILED, 10% with 1-3 retries (extra updates), rest complete
                val updates = mutableListOf<Map<String, Any?>>(); val deletes = mutableListOf<Map<String, Any?>>()
                for (r in recs) {
                    val roll = rnd.nextInt(100)
                    val id = r["id"]
                    if (roll < 10) repeat(1 + rnd.nextInt(3)) { updates += ctx.paramsFor(cat.getValue("upd_retry"), 0).toMutableMap().also { it["id"] = id } }
                    when {
                        roll < 2 -> updates += ctx.paramsFor(cat.getValue("upd_fail"), 0).toMutableMap().also { it["id"] = id }
                        rnd.nextInt(100) < 30 -> deletes += mapOf("id" to id)
                        else -> updates += ctx.paramsFor(upd, 0).toMutableMap().also { it["id"] = id }
                    }
                }
                // updates of different kinds share SQL; per-row params already contain the right values
                batchExec(upd, updates); if (deletes.isNotEmpty()) batchExec(del, deletes); c.commit()
                seq += m
                if (seq % 100_000 == 0) println("  churn $seq/$n")
            }
            // retention: delete oldest COMPLETED in batches of 1000 until ~n rows were removed
            var removed = 0
            val pr = hashMapOf<String, Any?>("status" to "COMPLETED", "cutoff" to tsOfMicros(T0_MICROS - 1 * SEC), "limit" to 1000)
            c.autoCommit = true
            while (removed < n) {
                val ids = Bench.exec(c, idsQ, pr, 1000, collect = true).data!!.map { it[0]!! }
                if (ids.isEmpty()) break
                c.autoCommit = false; removed += Bench.exec(c, delIds, mapOf("ids" to ids), 0).rows; c.commit(); c.autoCommit = true
            }
            rep.appendLine("removed_by_retention: $removed").appendLine("churn_seconds: ${(System.nanoTime() - t0) / 1_000_000_000}")
            rep.appendLine("== after churn (no manual VACUUM/ANALYZE) ==").append(cm.dialect.sizeReport(c))
        }
        File(cm.outDir, "churn.txt").writeText(rep.toString()); println(rep)
    }
}
