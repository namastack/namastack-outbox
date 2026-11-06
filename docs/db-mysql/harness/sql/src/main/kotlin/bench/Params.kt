package bench

import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID

/** Scenario-dependent parameter sets for catalog queries. */
/** `nonce` is a shared global so that helper contexts in worker threads see it. */
object Nonce { @Volatile var value = "" }

class Ctx(
    val d: Dialect, val c: Connection, val scenario: String, val batch: Int, val owned: List<Int>, val slots: Int,
) {
    val now: Timestamp = tsOfMicros(T0_MICROS)
    private var insertSeq = 0
    /** Mixed into generated insert ids so repeated runs on the same schema do not collide (empty = deterministic). */
    val nonce get() = Nonce.value
    private val rnd = java.util.Random(42)

    private fun sample(sql: String, max: Int, vararg binds: Any?): List<String> {
        val q = if (System.getenv("HARNESS_SAMPLE_ORDERED") != "0" && !sql.contains("ORDER BY")) "$sql ORDER BY 1" else sql
        // MySQL: the row limit is part of the SQL (same rows as the client-side truncation, but the server stops early on large tables)
        val q2 = if (d == Dialect.MYSQL && !q.contains(" LIMIT ")) "$q LIMIT $max" else q
        c.prepareStatement(q2).use { ps ->
            NamedSql.bind(ps, binds.toList()); ps.maxRows = max
            ps.executeQuery().use { rs -> val out = mutableListOf<String>(); while (rs.next()) out += rs.getString(1); return out }
        }
    }

    private val ownedIn get() = owned.joinToString(",")
    val dueKeys: List<String> by lazy {
        sample("SELECT record_key FROM outbox_record WHERE status='NEW' AND completed_at IS NULL AND next_retry_at <= ? AND partition_no IN ($ownedIn)", 500, now).distinct()
    }
    val dueIds: List<String> by lazy {
        sample("SELECT id FROM outbox_record WHERE status='NEW' AND completed_at IS NULL AND next_retry_at <= ? AND partition_no IN ($ownedIn)", maxOf(slots, 50), now)
    }
    val completedIds: List<String> by lazy {
        sample("SELECT id FROM outbox_record WHERE status='COMPLETED' AND partition_no IN ($ownedIn)", maxOf(slots, 50))
    }
    val sampleKey: String by lazy { dueKeys.firstOrNull() ?: sample("SELECT record_key FROM outbox_record", 1).firstOrNull() ?: "none" }
    val samplePartition: Int by lazy { owned.firstOrNull { it == Hasher.partition(sampleKey) } ?: owned.first() }

    /** Keys returned by the strict key query (what the scheduler passes to the page query). Filled by the bench. */
    var pageKeys: List<String> = emptyList()

    fun statusFor(id: String) = when {
        id == "count_failed" || id == "delete_by_status_failed" || id.contains("failed") -> "FAILED"
        id == "count_completed" || id.contains("completed") -> "COMPLETED"
        else -> "NEW"
    }

    fun limitFor(q: CatalogQuery): Int = when (q.maxRows) { "one" -> 1; "retention" -> 1000; else -> batch }

    fun paramsFor(q: CatalogQuery, rep: Int): Map<String, Any?> {
        val p = hashMapOf<String, Any?>()
        p["partitions"] = owned
        p["status"] = statusFor(q.id)
        p["now"] = now
        p["limit"] = limitFor(q)
        p["cutoff"] = tsOfMicros(T0_MICROS - (if (q.id.contains("failed")) 30 * DAY else 7 * DAY))
        p["statuses"] = if (q.id == "count_statuses_all") listOf("NEW", "FAILED", "COMPLETED") else listOf("NEW", "FAILED")
        p["partitionNo"] = samplePartition
        p["recordKey"] = sampleKey
        p["recordKeys"] = pageKeys.ifEmpty { dueKeys.take(batch) }
        when (q.id) {
            "delete_by_ids" -> p["ids"] = retentionIds
            "upd_complete", "upd_retry", "upd_fail" -> {
                p["id"] = dueIds[rep % dueIds.size]
                p["status"] = when (q.id) { "upd_complete" -> "COMPLETED"; "upd_retry" -> "NEW"; else -> "FAILED" }
                p["completedAt"] = if (q.id == "upd_complete") now else Null(Types.TIMESTAMP)
                p["failureCount"] = when (q.id) { "upd_complete" -> 0; "upd_retry" -> 1; else -> 4 }
                p["failureReason"] = if (q.id == "upd_complete") Null(Types.VARCHAR) else "java.lang.IllegalStateException: downstream call failed"
                p["nextRetryAt"] = if (q.id == "upd_retry") tsOfMicros(T0_MICROS + MIN) else now
            }
            "delete_by_id" -> p["id"] = (completedIds.ifEmpty { dueIds })[rep % maxOf(1, (completedIds.ifEmpty { dueIds }).size)]
            "insert" -> insertParams(p, ++insertSeq)
        }
        if (q.id == "delete_by_key_status") p["status"] = "NEW"
        return p
    }

    val retentionIds: List<String> by lazy {
        sample("SELECT id FROM outbox_record WHERE status='COMPLETED' AND completed_at < ? ORDER BY completed_at", 1000, tsOfMicros(T0_MICROS - 7 * DAY))
    }

    fun insertParams(p: MutableMap<String, Any?>, seq: Int, createdMicros: Long = T0_MICROS, keyPrefix: String = "order-bench-") {
        val key = keyPrefix + seq.toString().padStart(20, '0')
        val v4 = UUID.nameUUIDFromBytes("ins-$nonce-$keyPrefix-$seq".toByteArray())
        p["id"] = (if (ID_V7) uuidV7(createdMicros, seq.toLong(), v4.leastSignificantBits) else v4).toString()
        p["status"] = "NEW"; p["recordKey"] = key
        p["recordType"] = Realisation.TYPES[seq % 5]
        p["payload"] = """{"d":"${"x".repeat(490)}"}"""
        p["context"] = if (seq % 2 == 0) Null(Types.VARCHAR) else """{"traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}"""
        p["partitionNo"] = Hasher.partition(key)
        p["createdAt"] = tsOfMicros(createdMicros)
        p["completedAt"] = Null(Types.TIMESTAMP)
        p["failureCount"] = 0; p["failureReason"] = Null(Types.VARCHAR)
        p["nextRetryAt"] = tsOfMicros(createdMicros); p["handlerId"] = Realisation.HANDLERS[seq % 3]
    }
}

fun ownedPartitions(mode: String): List<Int> = when (mode) {
    "all" -> (0 until 256).toList()
    "mod4" -> (0 until 256).filter { it % 4 == 0 }
    else -> mode.split(",").map { it.trim().toInt() }
}
