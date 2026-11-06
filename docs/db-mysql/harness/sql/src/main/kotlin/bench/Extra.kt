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
        val ins = cat.getValue("insert"); val upd = cat.getValue("upd_complete"); val updRetry = cat.getValue("upd_retry"); val updFail = cat.getValue("upd_fail"); val del = cat.getValue("delete_by_id")
        val out = StringBuilder("metric,value\n")
        val dir = cm.outDir
        cm.dialect.connect(cm.schema).use { c0 ->
            val ctx = Ctx(cm.dialect, c0, cm.scenario, cm.batch, cm.owned, 3 * n + 10)
            val pg = cm.dialect == Dialect.POSTGRES; val maria = cm.dialect.isInnoDb; val dd = cm.dialect
            if (maria) { Inno.settle(dd, c0); out.appendLine("innodb_history_list_length_at_start,\"${Maria.settleNote}\"") }
            fun scalar(sql: String): String = c0.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }
            fun idxReport(tag: String) {
                if (maria) {   // InnoDB: plain ANALYZE TABLE, then per-index sizes from mysql.innodb_index_stats and the tablespace file size
                    val sizes = Maria.indexSizes(c0, true)
                    for ((k, v) in sizes) out.appendLine("index_bytes_$tag.$k,$v")
                    if (tag == "before") out.appendLine("index_count,${sizes.size}")
                    out.appendLine("table_bytes_$tag,${sizes["PRIMARY"] ?: 0}")
                    out.appendLine("indexes_total_bytes_$tag,${sizes.filterKeys { it != "PRIMARY" }.values.sum()}")
                    Inno.tablespace(dd, c0)?.let { out.appendLine("tablespace_file_bytes_$tag,${it.first}"); out.appendLine("tablespace_allocated_bytes_$tag,${it.second}") }
                    return
                }
                if (!pg) return
                c0.createStatement().use { st -> st.executeQuery("SELECT indexrelid::regclass::text, pg_relation_size(indexrelid) FROM pg_index WHERE indrelid='outbox_record'::regclass ORDER BY 1").use { rs ->
                    var n = 0; while (rs.next()) { n++; out.appendLine("index_bytes_$tag.${rs.getString(1)},${rs.getLong(2)}") }; if (tag == "before") out.appendLine("index_count,$n") } }
                out.appendLine("table_bytes_$tag,${scalar("SELECT pg_table_size('outbox_record')")}")
                out.appendLine("indexes_total_bytes_$tag,${scalar("SELECT pg_indexes_size('outbox_record')")}")
            }
            idxReport("before")
            // PostgreSQL: WAL bytes (pg_current_wal_lsn difference over each phase, after a CHECKPOINT).
            // MariaDB: deltas of the GLOBAL InnoDB counters over each phase (redo bytes, pages/data written, buffer pool reads); the lane's server
            // has no other client. Dirty pages are flushed (Maria.flushAll) before and after each phase, so pages/data written = pages the phase dirtied.
            fun <T> walPhase(label: String, writes: () -> Int): Int {
                if (maria) {
                    Maria.flushAll(c0)   // clean baseline: no dirty pages of earlier phases
                    val g0 = Inno.globals(dd, c0); val w = writes(); val dirty = Maria.flushAll(c0); val d = Inno.delta(dd, g0, Inno.globals(dd, c0))   // flush: pages_written/data_written = pages this phase dirtied
                    out.appendLine("$label.writes,$w"); out.appendLine("$label.dirty_pages_left_after_flush,$dirty")
                    for ((k, v) in d) { if (k == "Innodb_buffer_pool_pages_dirty") continue
                        val short = k.removePrefix("Innodb_"); out.appendLine("$label.$short.total,$v"); out.appendLine("$label.$short.per_write,%.2f".format(v.toDouble() / maxOf(1, w))) }
                    return w
                }
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
                if (lat.isEmpty()) { out.appendLine("$label.count,0"); return }
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
            val all = ctx.dueIds   // sampled before the insert phases so that the update slices are the same rows for every variant
            // warm up
            insertRun("insert_warmup", "order-wcw-", 1, 200)
            if (maria) Maria.flushAll(c0)
            val gStart = if (maria) Inno.globals(dd, c0) else emptyMap()
            walPhase<Unit>("insert_1thread") { insertRun("insert_1thread", "order-wc1-", 1, n); n }
            walPhase<Unit>("insert_${threads}threads") { insertRun("insert_${threads}threads", "order-wc4-", threads, n / threads); n / threads * threads }
            // committed status updates on due records (disjoint slices: complete, retry, fail), then committed deletes of the completed ones
            val nu = minOf(n, all.size / 3)   // three disjoint slices of due ids; S1 has only 150 due records
            if (nu < n) out.appendLine("note_update_phases_limited_to,$nu")
            val slice = { k: Int -> all.drop(k * nu).take(nu) }
            c0.autoCommit = true
            fun updPhase(label: String, q: CatalogQuery, ids: List<String>) {
                val lats = mutableListOf<Double>(); var t0 = System.nanoTime()
                walPhase<Unit>(label) {
                    t0 = System.nanoTime()
                    for ((i, id) in ids.withIndex()) {
                        val p = ctx.paramsFor(q, i).toMutableMap(); p["id"] = id
                        val t = System.nanoTime(); Bench.exec(c0, q, p, 0); lats += (System.nanoTime() - t) / 1e6
                    }
                    ids.size
                }
                stats(label, lats, (System.nanoTime() - t0) / 1e6)
            }
            val completeIds = slice(0)
            updPhase("upd_complete_committed", upd, completeIds)
            updPhase("upd_retry_committed", updRetry, slice(1))
            updPhase("upd_fail_committed", updFail, slice(2))
            run { // delete_by_id on the records completed above (status COMPLETED, as retention would remove them)
                val lats = mutableListOf<Double>(); var t0 = System.nanoTime()
                walPhase<Unit>("delete_by_id_committed") {
                    t0 = System.nanoTime()
                    for (id in completeIds) { val t = System.nanoTime(); Bench.exec(c0, del, mapOf("id" to id), 0); lats += (System.nanoTime() - t) / 1e6 }
                    completeIds.size
                }
                stats("delete_by_id_committed", lats, (System.nanoTime() - t0) / 1e6)
            }
            if (maria) {
                val dAll = Inno.delta(dd, gStart, Inno.globals(dd, c0))
                for ((k, v) in dAll) if (k != "Innodb_buffer_pool_pages_dirty") out.appendLine("all_phases.${k.removePrefix("Innodb_")}.total,$v")
            }
            idxReport("after")
            if (dd == Dialect.MYSQL) { out.appendLine("binlog_bytes_at_end,${MySql.binlogBytes(c0)}") }
            File(dir, "writecost.csv").writeText(out.toString())
            File(dir, "sizes-before-write.txt").writeText(before)
            File(dir, "sizes-after-write.txt").writeText(cm.dialect.sizeReport(c0))
            println(out)
        }
        MySql.purgeBinlogs(cm.dialect)
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
        val maria = cm.dialect.isInnoDb; val dd = cm.dialect
        var g0: Map<String, Long> = emptyMap(); var hll0 = -1L; var settle = ""
        if (maria) cm.dialect.connect(cm.schema).use { c -> settle = Inno.settle(dd, c); hll0 = Inno.hll(dd, c); g0 = Inno.globals(dd, c) }
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
        var mariaTxt = ""
        if (maria) cm.dialect.connect(cm.schema).use { c ->
            val d = Inno.delta(dd, g0, Inno.globals(dd, c))
            mariaTxt = "innodb_history_list_length: start $settle; before run $hll0; end ${Inno.hll(dd, c)}\n" +
                "innodb_global_deltas: " + d.entries.filter { it.key != "Innodb_buffer_pool_pages_dirty" }.joinToString(" ") { "${it.key.removePrefix("Innodb_")}=${it.value}" } + "\n" +
                "lock_waits=${d["Innodb_row_lock_waits"]} deadlocks=${d["Innodb_deadlocks"]} row_lock_time_ms=${d["Innodb_row_lock_time"]}\n"
        }
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
$mariaTxt""".format(secs, processed.get() / secs, inserted.get() / secs)
        File(cm.outDir, "concurrency-$tag.txt").writeText(txt); println(txt)
        MySql.purgeBinlogs(cm.dialect)
    }
}

object Churn {
    fun run(cm: Common, o: Opts) {
        Nonce.value = System.nanoTime().toString()
        val n = o.int("inserts", 500_000); val chunk = 1000
        val cat = Catalog.load(cm.catalogRoot, cm.catalogVariant, cm.dialect, cm.queries)
        val ins = cat.getValue("insert"); val upd = cat.getValue("upd_complete"); val del = cat.getValue("delete_by_id")
        val rep = StringBuilder()
        cm.dialect.connect(cm.schema).use { c ->
            val ctx = Ctx(cm.dialect, c, cm.scenario, cm.batch, cm.owned, 10)
            val maria = cm.dialect.isInnoDb; val dd = cm.dialect
            if (maria) { Inno.settle(dd, c); rep.appendLine("innodb_history_list_length_at_start: ${Maria.settleNote}") }
            val gc0 = if (maria) Inno.globals(dd, c) else emptyMap()
            rep.appendLine("== before ==").append(cm.dialect.sizeReport(c))
            if (maria) rep.appendLine("history_list_length_before: ${Inno.hll(dd, c)}")
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
            // retention (harness-internal, identical SQL for every variant; the variants' own retention statements are Priority 3 and never run):
            // the n oldest COMPLETED rows of the snapshot (fixed ordering created_at, id), deleted by primary key in batches of 1000, untimed.
            var removed = 0
            c.autoCommit = true
            val victims = ArrayList<String>(n)
            c.createStatement().use { st -> st.executeQuery("SELECT id FROM ${cm.dialect.snapTable} WHERE status = 'COMPLETED' ORDER BY created_at, id LIMIT $n").use { rs -> while (rs.next()) victims += rs.getString(1) } }
            val delSql = "DELETE FROM outbox_record WHERE id IN (" + (1..chunk).joinToString(",") { "?" } + ")"
            c.autoCommit = false
            for (part in victims.chunked(chunk)) {
                val sql = if (part.size == chunk) delSql else "DELETE FROM outbox_record WHERE id IN (" + part.joinToString(",") { "?" } + ")"
                c.prepareStatement(sql).use { ps -> part.forEachIndexed { i, id -> ps.setString(i + 1, id) }; removed += ps.executeUpdate() }
                c.commit()
            }
            c.autoCommit = true
            rep.appendLine("removed_by_retention (harness-internal PK delete of the $n oldest snapshot COMPLETED rows): $removed").appendLine("churn_seconds: ${(System.nanoTime() - t0) / 1_000_000_000}")
            rep.appendLine("== after churn (no manual VACUUM/ANALYZE/OPTIMIZE) ==").append(cm.dialect.sizeReport(c))
            if (maria) {
                rep.appendLine("history_list_length_after: ${Inno.hll(dd, c)}")
                rep.appendLine("innodb_global_deltas: " + Inno.delta(dd, gc0, Inno.globals(dd, c)).entries.filter { it.key != "Innodb_buffer_pool_pages_dirty" }.joinToString(" ") { "${it.key.removePrefix("Innodb_")}=${it.value}" })
            }
        }
        File(cm.outDir, "churn.txt").writeText(rep.toString()); println(rep)
        MySql.purgeBinlogs(cm.dialect)
    }
}
