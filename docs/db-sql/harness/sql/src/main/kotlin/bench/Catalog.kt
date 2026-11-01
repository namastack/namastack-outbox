package bench

import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types

/** Marker for a typed SQL NULL. */
class Null(val sqlType: Int)

/**
 * One catalog entry = one file `<id>.sql`: header lines `-- key: value` and the SQL text with named parameters
 * (`:name`), exactly the text the repository builds (whitespace collapsed).
 * Header keys: id, group, kind (read|write|delete), maxrows (none|batchSize|one|retention), source.
 */
class CatalogQuery(val id: String, val sql: String, val headers: Map<String, String>, val file: File) {
    val group get() = headers["group"] ?: ""
    val kind get() = headers["kind"] ?: "read"
    val maxRows get() = headers["maxrows"] ?: "none"
    val paramNames: List<String> by lazy { NamedSql.parse(sql).second.distinct() }
}

object Catalog {
    val ORDER = listOf(
        "keys_strict", "keys_nonstrict", "pages",
        "insert", "upd_complete", "upd_retry", "upd_fail", "delete_by_id",
        "count_new", "count_failed", "count_completed", "count_statuses", "count_statuses_all", "count_partitions", "count_partition_single",
        "oldest_created", "oldest_due_strict", "oldest_due_nonstrict",
        "find_completed_before", "find_failed_before", "ids_completed_before", "ids_failed_before",
        "delete_by_ids", "delete_by_status_failed", "delete_by_key_status",
        "find_pending_all", "find_failed_all", "find_incomplete_by_key",
    )

    fun load(root: File, variant: String, dialect: Dialect, overrideDir: File?): Map<String, CatalogQuery> {
        val dir = File(root, "$variant/${dialect.id}")
        require(dir.isDirectory) { "no catalog at $dir (run bin/extract-catalog.sh)" }
        val out = linkedMapOf<String, CatalogQuery>()
        fun read(f: File) {
            val lines = f.readLines()
            val headers = lines.takeWhile { it.startsWith("--") }.mapNotNull { l ->
                val m = Regex("^--\\s*([A-Za-z_.]+):\\s*(.*)$").find(l); m?.let { it.groupValues[1] to it.groupValues[2].trim() }
            }.toMap()
            val sql = lines.dropWhile { it.startsWith("--") }.joinToString(" ").trim().removeSuffix(";")
            val id = f.nameWithoutExtension
            out[id] = CatalogQuery(id, sql, headers + ("id" to id), f)
        }
        dir.listFiles { f -> f.extension == "sql" }!!.sortedBy { it.name }.forEach { read(it) }
        if (overrideDir != null) {
            overrideDir.listFiles { f -> f.extension == "sql" }?.sortedBy { it.name }?.forEach { f ->
                val old = out[f.nameWithoutExtension]
                read(f)
                // inherit header values (maxrows, group, kind) the override does not define
                if (old != null) {
                    val n = out[f.nameWithoutExtension]!!
                    out[n.id] = CatalogQuery(n.id, n.sql, old.headers + n.headers, f)
                }
            }
        }
        return out
    }
}

object NamedSql {
    /** Returns the JDBC sql (named parameters replaced by '?') and the parameter names in order of appearance. */
    fun parse(sql: String): Pair<String, List<String>> {
        val sb = StringBuilder(); val names = mutableListOf<String>()
        var i = 0; var quote = false
        while (i < sql.length) {
            val ch = sql[i]
            if (ch == '\'') { quote = !quote; sb.append(ch); i++; continue }
            if (!quote && ch == ':' && i + 1 < sql.length && sql[i + 1] == ':') { sb.append("::"); i += 2; continue }
            if (!quote && ch == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_')) {
                var j = i + 1; while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                names += sql.substring(i + 1, j); sb.append('?'); i = j; continue
            }
            sb.append(ch); i++
        }
        return sb.toString() to names
    }

    /** Expand collection parameters to `?,?,...` like Spring's NamedParameterUtils and return flat values. */
    fun expand(sql: String, params: Map<String, Any?>): Pair<String, List<Any?>> {
        val sb = StringBuilder(); val values = mutableListOf<Any?>()
        var i = 0; var quote = false
        while (i < sql.length) {
            val ch = sql[i]
            if (ch == '\'') { quote = !quote; sb.append(ch); i++; continue }
            if (!quote && ch == ':' && i + 1 < sql.length && sql[i + 1] == ':') { sb.append("::"); i += 2; continue }
            if (!quote && ch == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_')) {
                var j = i + 1; while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                val name = sql.substring(i + 1, j)
                require(params.containsKey(name)) { "missing parameter :$name" }
                val v = params[name]
                if (v is Collection<*>) { sb.append(v.joinToString(",") { "?" }); values.addAll(v) } else { sb.append('?'); values.add(v) }
                i = j; continue
            }
            sb.append(ch); i++
        }
        return sb.toString() to values
    }

    fun bind(ps: PreparedStatement, values: List<Any?>) {
        values.forEachIndexed { idx, v ->
            val i = idx + 1
            when (v) {
                null -> ps.setNull(i, Types.NULL)
                is Null -> ps.setNull(i, v.sqlType)
                is Timestamp -> ps.setTimestamp(i, v)
                is String -> ps.setString(i, v)
                is Int -> ps.setInt(i, v)
                is Long -> ps.setLong(i, v)
                else -> ps.setObject(i, v)
            }
        }
    }

    /** Inline values as literals (plan capture on engines whose EXPLAIN cannot take bind parameters). */
    fun literalize(d: Dialect, sql: String, values: List<Any?>): String {
        val sb = StringBuilder(); var vi = 0
        for (ch in sql) {
            if (ch == '?') {
                val v = values[vi++]
                sb.append(when (v) {
                    null, is Null -> "NULL"
                    is Int, is Long -> v.toString()
                    is Timestamp -> {
                        val s = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(java.time.ZoneOffset.UTC).format(v.toInstant())
                        when (d) {
                            Dialect.ORACLE -> "TIMESTAMP '$s +00:00'"
                            Dialect.H2 -> "TIMESTAMP WITH TIME ZONE '$s+00:00'"
                            Dialect.SQLSERVER -> "CAST('$s' AS DATETIME2(6))"
                            else -> "'$s'"
                        }
                    }
                    else -> "'" + v.toString().replace("'", "''") + "'"
                })
            } else sb.append(ch)
        }
        return sb.toString()
    }
}
