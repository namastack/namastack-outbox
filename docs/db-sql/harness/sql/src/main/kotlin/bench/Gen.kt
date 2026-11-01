package bench

import org.apache.commons.codec.digest.MurmurHash3
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/** Partition hash: identical to io.namastack.outbox.partition.PartitionHasher (MurmurHash3 x86-32, seed 0). */
object Hasher {
    fun partition(key: String): Int {
        val b = key.toByteArray(Charsets.UTF_8)
        return (MurmurHash3.hash32x86(b, 0, b.size, 0) and 0x7fffffff) % 256
    }
}

const val SEC = 1_000_000L
const val MIN = 60 * SEC
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR
val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")
val T0_MICROS: Long = T0.epochSecond * SEC

fun micros(i: Instant): Long = i.epochSecond * SEC + i.nano / 1000
fun instantOfMicros(m: Long): Instant = Instant.ofEpochSecond(Math.floorDiv(m, SEC), Math.floorMod(m, SEC) * 1000)
fun tsOfMicros(m: Long): Timestamp = Timestamp.from(instantOfMicros(m))

/** C = COMPLETED, D = NEW due, F = NEW with a future retry, X = FAILED. */
enum class Kind { C, D, F, X }

/** Small deterministic generator (splitmix64) addressable by (seed, stream). */
class Rng(seed: Long, stream: Long) {
    private var s = seed * -7046029254386353131L + stream * -4658895280553007687L + 0x1234567L
    fun next(): Long {
        s += -7046029254386353131L
        var z = s
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
    fun int(bound: Int): Int = ((next() ushr 1) % bound).toInt()
    fun range(lo: Long, hiInclusive: Long): Long = lo + (next() ushr 1) % (hiInclusive - lo + 1)
    fun double(): Double = (next() ushr 11) / (1L shl 53).toDouble()
}

class ScenarioSpec(
    val id: String,
    val totalRows: Int,
    val keys: Int,
    val payloadBytes: Int = 500,
    val spanMicros: Long = 30 * DAY,
    val recentMicros: Long = HOUR,
    /** non-COMPLETED records of key k, in chronological order, after its COMPLETED history. */
    val specials: (Int) -> List<Kind>,
    /** allowed number of COMPLETED history records of key k. */
    val hist: (Int) -> IntRange,
    val description: String,
)

object Scenarios {
    private fun spec(id: String): ScenarioSpec = when (id.uppercase()) {
        "S1" -> ScenarioSpec("S1", 10_000, 5_000,
            specials = { k -> when { k < 150 -> listOf(Kind.D); k < 180 -> listOf(Kind.F); k < 200 -> listOf(Kind.X); else -> emptyList() } },
            hist = { k -> if (k < 200) 0..2 else 1..3 },
            description = "9800 COMPLETED, 150 NEW due, 30 NEW future, 20 FAILED; 5000 keys")
        "S2", "S2L" -> {
            val big = id.uppercase() == "S2L"
            val scale = if (big) 10 else 1
            val due = 1000; val fut = 200; val fail = 12800 * scale; val blockers = 50   // blockers = 5% of due keys
            val failKeys = fail - (blockers - blockers / 2); val futKeys = fut - blockers / 2
            val special = due + failKeys + futKeys
            ScenarioSpec(id.uppercase(), 1_000_000 * scale, 200_000 * scale, payloadBytes = if (big) 200 else 500,
                specials = { k ->
                    when {
                        k < blockers -> listOf(if (k % 2 == 0) Kind.X else Kind.F, Kind.D)
                        k < due -> listOf(Kind.D)
                        k < due + failKeys -> listOf(Kind.X)
                        k < special -> listOf(Kind.F)
                        else -> emptyList()
                    }
                },
                hist = { k -> if (k < special) 0..7 else 1..9 },
                description = "98.6% COMPLETED, $due NEW due ($blockers blocked by older FAILED/future NEW), $fut NEW future, $fail FAILED; keys 1-9 records")
        }
        "S3" -> ScenarioSpec("S3", 5_000, 4_000, spanMicros = 7 * DAY,
            specials = { k -> when { k < 1000 -> listOf(Kind.X, Kind.D); k < 3000 -> listOf(Kind.D); k < 3500 -> listOf(Kind.F); else -> listOf(Kind.X) } },
            hist = { 0..0 }, description = "3000 NEW due, 500 NEW future, 1500 FAILED, 0 COMPLETED")
        "S4" -> ScenarioSpec("S4", 2_000_000, 1_500_000,
            specials = { k -> if (k < 1_000_000) listOf(Kind.D) else emptyList() },
            hist = { k -> if (k < 1_000_000) 0..0 else 2..2 },
            description = "1M NEW due (1 per key) + 1M COMPLETED (500k keys x 2)")
        "S5" -> ScenarioSpec("S5", 500_000, 100, recentMicros = 6 * HOUR,
            specials = { k -> when { k < 20 -> listOf(Kind.X) + List(1999) { Kind.D }; k < 40 -> listOf(Kind.F) + List(1999) { Kind.D }; else -> List(2000) { Kind.D } } },
            hist = { 3000..3000 }, description = "100 hot keys x 2000 NEW + 3000 COMPLETED; 20 FAILED heads, 20 future heads, 60 due heads")
        "S6" -> ScenarioSpec("S6", 1_000_000, 300_000,
            specials = { k -> when { k < 5_000 -> listOf(Kind.D); k < 55_000 -> listOf(Kind.F, Kind.F); k < 150_000 -> listOf(Kind.F); else -> emptyList() } },
            hist = { k -> if (k < 150_000) 0..9 else 1..9 },
            description = "800k COMPLETED, 195k NEW future, 5k NEW due; 150k NEW keys")
        "S7" -> ScenarioSpec("S7", 1_000_000, 300_000, spanMicros = 90 * DAY,
            specials = { k -> when { k < 10_000 -> listOf(Kind.D); k < 140_000 -> listOf(Kind.X, Kind.X, Kind.X); else -> emptyList() } },
            hist = { k -> if (k < 140_000) 0..9 else 1..9 },
            description = "600k COMPLETED, 390k FAILED (spread over 90d), 10k NEW due")
        else -> error("unknown scenario $id (S1 S2 S2L S3 S4 S5 S6 S7; S8 = S2/S4 data with --owned mod4; S9/S10 run on S2 data)")
    }
    fun of(id: String): ScenarioSpec = spec(id)
}

/** Row ready for loading (strings already final). */
class Row(
    val id: String, val status: String, val key: String, val type: String, val payload: String, val context: String?,
    val createdAt: Long, val completedAt: Long?, val failureCount: Int, val failureReason: String?, val nextRetryAt: Long,
    val partition: Int, val handler: String,
)

/** Deterministic, seeded realisation of a scenario, emitted in created_at order (heap order of a live system). */
class Realisation(val spec: ScenarioSpec, val seed: Long, private val truncateSeconds: Boolean) {
    private val h = IntArray(spec.keys)
    private val rowStart = IntArray(spec.keys + 1)
    private val created: LongArray
    private val order: LongArray

    companion object {
        val TYPES = listOf("io.namastack.outbox.bench.OrderCreatedPayload", "io.namastack.outbox.bench.OrderShippedPayload",
            "io.namastack.outbox.bench.PaymentCapturedPayload", "io.namastack.outbox.bench.InvoiceIssuedPayload", "io.namastack.outbox.bench.CustomerUpdatedPayload")
        val HANDLERS = listOf("io.namastack.outbox.bench.handlers.OrderEventsHandler#handleOrderEvent(io.namastack.outbox.bench.OrderCreatedPayload)",
            "io.namastack.outbox.bench.handlers.NotificationHandler#sendNotification(io.namastack.outbox.bench.OrderShippedPayload)",
            "io.namastack.outbox.bench.handlers.BillingHandler#publishToBilling(io.namastack.outbox.bench.PaymentCapturedPayload)")
        const val ALPHA = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        fun pad(k: Int): String { val s = k.toString(); return "order-" + "0".repeat(30 - s.length) + s }
    }

    private val payloads: Array<String>
    private val contexts: Array<String>
    private val reasons: Array<String>

    init {
        val r = Rng(seed, 99)
        fun rand(n: Int): String { val sb = StringBuilder(n); repeat(n) { sb.append(ALPHA[r.int(ALPHA.length)]) }; return sb.toString() }
        val overhead = """{"d":""}""".length
        payloads = Array(4096) { """{"d":"${rand(spec.payloadBytes - overhead)}"}""" }
        contexts = Array(64) { """{"traceparent":"00-${rand(32)}-${rand(16)}-01","tenant":"${rand(12)}"}""" }
        reasons = Array(64) { "java.lang.IllegalStateException: downstream call failed: ${rand(150)}" }

        // history sizes, then adjust to hit totalRows exactly
        var sum = 0L
        for (k in 0 until spec.keys) { val rg = spec.hist(k); h[k] = rg.first + Rng(seed, k.toLong()).int(rg.last - rg.first + 1); sum += h[k] + spec.specials(k).size }
        var diff = spec.totalRows - sum
        var guard = 0
        while (diff != 0L && guard++ < 200) {
            for (k in 0 until spec.keys) {
                if (diff == 0L) break
                val rg = spec.hist(k)
                if (diff > 0 && h[k] < rg.last) { h[k]++; diff-- } else if (diff < 0 && h[k] > rg.first) { h[k]--; diff++ }
            }
        }
        require(diff == 0L) { "cannot reach ${spec.totalRows} rows for ${spec.id} (diff $diff)" }
        for (k in 0 until spec.keys) rowStart[k + 1] = rowStart[k] + h[k] + spec.specials(k).size
        require(rowStart[spec.keys] == spec.totalRows)

        created = LongArray(spec.totalRows)
        val start = T0_MICROS - spec.spanMicros
        for (k in 0 until spec.keys) {
            val sp = spec.specials(k)
            val rr = Rng(seed, 1_000_000_000L + k)
            val n = h[k] + sp.size
            val spTimes = LongArray(sp.size) { i ->
                when (sp[i]) {
                    Kind.D, Kind.F -> rr.range(T0_MICROS - spec.recentMicros, T0_MICROS - SEC)
                    Kind.X -> rr.range(start, T0_MICROS - 2 * HOUR)
                    Kind.C -> error("C in specials")
                }
            }
            spTimes.sort()
            val histEnd = if (sp.isEmpty()) T0_MICROS - 2 * HOUR else spTimes[0]
            val hist = LongArray(h[k]) { rr.range(start, maxOf(start, histEnd)) }
            hist.sort()
            var prev = Long.MIN_VALUE
            val base = rowStart[k]
            for (i in 0 until n) {
                var t = if (i < h[k]) hist[i] else spTimes[i - h[k]]
                if (truncateSeconds) t -= Math.floorMod(t, SEC)
                if (i > 0 && rr.int(100) < 2) t = prev           // ~2 % ties with the predecessor (same key)
                else if (t <= prev) t = if (truncateSeconds) prev else prev + 1
                created[base + i] = t; prev = t
            }
        }
        order = LongArray(spec.totalRows) { ((created[it] - start) shr 6 shl 24) or it.toLong() }
        order.sort()
    }

    private fun keyOf(ord: Int): Int {
        var lo = 0; var hi = spec.keys - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (rowStart[mid] <= ord) lo = mid else hi = mid - 1 }
        return lo
    }

    fun forEach(action: (Row) -> Unit) {
        for (o in order) {
            val ord = (o and 0xffffffL).toInt()
            val k = keyOf(ord); val idx = ord - rowStart[k]
            val kind = if (idx < h[k]) Kind.C else spec.specials(k)[idx - h[k]]
            action(row(ord, k, kind))
        }
    }

    private fun row(ord: Int, k: Int, kind: Kind): Row {
        val r = Rng(seed, 5_000_000_000L + ord)
        val a = r.next(); val b = r.next()
        val id = java.util.UUID(((a and -61441L) or 0x4000L), (b and 0x3fffffffffffffffL) or Long.MIN_VALUE).toString()
        val key = Realisation.pad(k)
        val c = created[ord]
        val tr = { v: Long -> if (truncateSeconds) v - Math.floorMod(v, SEC) else v }
        var completed: Long? = null; var fc = 0; var reason: String? = null; var next = c; val status: String
        when (kind) {
            Kind.C -> {
                status = "COMPLETED"; completed = tr(c + r.range(5_000, 2 * SEC))
                if (r.int(100) < 10) { fc = 1 + r.int(3); reason = reasons[r.int(64)]; next = tr(c + r.range(SEC, 60 * SEC)) }
            }
            Kind.D -> { status = "NEW" }
            Kind.F -> { status = "NEW"; fc = 1 + r.int(3); reason = reasons[r.int(64)]; next = tr(T0_MICROS + r.range(MIN, HOUR)) }
            Kind.X -> { status = "FAILED"; fc = 4; reason = reasons[r.int(64)]; next = minOf(tr(c + r.range(0, HOUR)), T0_MICROS - SEC) }
        }
        return Row(id, status, key, TYPES[k % 5], payloads[r.int(4096)], if (r.int(2) == 0) null else contexts[r.int(64)],
            c, completed, fc, reason, next, Hasher.partition(key), HANDLERS[k % 3])
    }
}

object Loader {
    private val COLS = "id, status, record_key, record_type, payload, context, created_at, completed_at, failure_count, failure_reason, next_retry_at, partition_no, handler_id"

    fun createSnapshot(d: Dialect, scenario: String, seed: Long) {
        val spec = Scenarios.of(scenario)
        val t = System.nanoTime()
        println("generating ${spec.id}: ${spec.description}")
        val real = Realisation(spec, seed, truncateSeconds = d == Dialect.MYSQL || d == Dialect.MARIADB)
        println("  timeline built in ${(System.nanoTime() - t) / 1_000_000} ms")
        d.resetSchema("snap")
        d.connect("snap").use { c ->
            c.autoCommit = true
            c.createStatement().use { it.execute(d.snapDdl()) }
            val t1 = System.nanoTime()
            if (d == Dialect.POSTGRES) copyPg(c, real) else insertBatch(d, c, real)
            println("  loaded ${spec.totalRows} rows in ${(System.nanoTime() - t1) / 1_000_000_000} s")
        }
        // Remember which scenario the snapshot holds
        java.io.File("build/snap-${d.id}.txt").also { it.parentFile?.mkdirs() }
            .writeText("scenario=${spec.id}\nseed=$seed\nrows=${spec.totalRows}\n")
    }

    private fun q(s: String?): String = if (s == null) "" else "\"" + s.replace("\"", "\"\"") + "\""

    private fun copyPg(c: Connection, real: Realisation) {
        val pg = c.unwrap(org.postgresql.PGConnection::class.java)
        val copy = pg.copyAPI.copyIn("COPY rec ($COLS) FROM STDIN WITH (FORMAT csv)")
        val buf = ByteArrayOutputStream(1 shl 20)
        fun ts(m: Long?) = if (m == null) "" else instantOfMicros(m).toString()
        real.forEach { r ->
            val line = buildString {
                append(r.id).append(',').append(r.status).append(',').append(r.key).append(',').append(r.type).append(',')
                append(q(r.payload)).append(',').append(q(r.context)).append(',').append(ts(r.createdAt)).append(',').append(ts(r.completedAt)).append(',')
                append(r.failureCount).append(',').append(q(r.failureReason)).append(',').append(ts(r.nextRetryAt)).append(',')
                append(r.partition).append(',').append(q(r.handler)).append('\n')
            }
            buf.write(line.toByteArray(Charsets.UTF_8))
            if (buf.size() > (1 shl 20) - 4096) { copy.writeToCopy(buf.toByteArray(), 0, buf.size()); buf.reset() }
        }
        if (buf.size() > 0) copy.writeToCopy(buf.toByteArray(), 0, buf.size())
        copy.endCopy()
    }

    private fun insertBatch(d: Dialect, c: Connection, real: Realisation) {
        c.autoCommit = false
        val ps = c.prepareStatement("INSERT INTO rec ($COLS) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")
        var n = 0
        real.forEach { r ->
            ps.setString(1, r.id); ps.setString(2, r.status); ps.setString(3, r.key); ps.setString(4, r.type)
            ps.setString(5, r.payload); if (r.context == null) ps.setNull(6, Types.VARCHAR) else ps.setString(6, r.context)
            ps.setTimestamp(7, tsOfMicros(r.createdAt))
            if (r.completedAt == null) ps.setNull(8, Types.TIMESTAMP) else ps.setTimestamp(8, tsOfMicros(r.completedAt))
            ps.setInt(9, r.failureCount); if (r.failureReason == null) ps.setNull(10, Types.VARCHAR) else ps.setString(10, r.failureReason)
            ps.setTimestamp(11, tsOfMicros(r.nextRetryAt)); ps.setInt(12, r.partition); ps.setString(13, r.handler)
            ps.addBatch()
            if (++n % 2000 == 0) { ps.executeBatch(); c.commit() }
            if (n % 200_000 == 0) println("    $n rows")
        }
        ps.executeBatch(); c.commit(); c.autoCommit = true
    }

    /** Build a run schema for a variant from the snapshot: DDL (tables), data copy, DDL (indexes), statistics. */
    fun prepare(d: Dialect, schema: String, ddlText: String) {
        val stmts = d.splitDdl(ddlText)
        val isIndex = { s: String -> Regex("^CREATE\\s+(UNIQUE\\s+)?INDEX|^CREATE\\s+STATISTICS", RegexOption.IGNORE_CASE).containsMatchIn(s) }
        val deferIndexes = d == Dialect.POSTGRES || d == Dialect.H2
        val first = if (deferIndexes) stmts.filter { !isIndex(it) } else stmts
        val later = if (deferIndexes) stmts.filter { isIndex(it) } else emptyList()
        d.resetSchema(schema)
        if (d == Dialect.ORACLE) d.admin().use { it.createStatement().execute("GRANT SELECT ON snap.rec TO $schema") }
        d.connect(schema).use { c ->
            c.autoCommit = true
            val st = c.createStatement()
            for (s in first) st.execute(s)
            val t = System.nanoTime()
            st.execute("INSERT INTO outbox_record ($COLS) SELECT $COLS FROM ${d.snapTable}")
            println("  copied snapshot in ${(System.nanoTime() - t) / 1_000_000_000} s")
            val t2 = System.nanoTime()
            for (s in later) st.execute(s)
            if (later.isNotEmpty()) println("  built indexes in ${(System.nanoTime() - t2) / 1_000_000_000} s")
            for (s in d.analyzeSql) st.execute(s)
        }
    }
}
