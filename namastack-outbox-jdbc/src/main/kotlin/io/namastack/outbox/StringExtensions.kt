package io.namastack.outbox

/**
 * Converts a multi-line SQL string to a single line by replacing newlines
 * and multiple whitespace characters with a single space.
 *
 * This is useful for SQL queries defined with triple-quoted strings to ensure
 * they appear as single lines in debug logs.
 *
 * Example:
 * ```kotlin
 * val query = """
 *     SELECT * FROM table
 *     WHERE id = :id
 * """.toSingleLine()
 * // Result: "SELECT * FROM table WHERE id = :id"
 * ```
 *
 * @return The string with all whitespace normalized to single spaces
 * @author Roland Beisel
 * @since 1.0.0
 */
internal fun String.toSingleLine(): String = trimIndent().replace(Regex("\\s+"), " ").trim()
