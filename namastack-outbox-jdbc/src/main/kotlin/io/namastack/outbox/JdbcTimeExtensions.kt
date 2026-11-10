package io.namastack.outbox

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Converts this instant to a [Timestamp] for binding as a JDBC parameter.
 *
 * @return The timestamp representing the same point in time
 * @author Aleksander Zamojski
 * @since 1.11.0
 */
internal fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

/**
 * Reads a non-null timestamp column as an [Instant].
 *
 * @param column The column label
 * @return The column value as an instant
 * @author Aleksander Zamojski
 * @since 1.11.0
 */
internal fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()

/**
 * Reads a nullable timestamp column as an [Instant].
 *
 * @param column The column label
 * @return The column value as an instant, or `null` if the value is SQL `NULL`
 * @author Aleksander Zamojski
 * @since 1.11.0
 */
internal fun ResultSet.getInstantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

/**
 * Reads a nullable timestamp column as an [Instant].
 *
 * @param columnIndex The 1-based column index
 * @return The column value as an instant, or `null` if the value is SQL `NULL`
 * @author Aleksander Zamojski
 * @since 1.11.0
 */
internal fun ResultSet.getInstantOrNull(columnIndex: Int): Instant? = getTimestamp(columnIndex)?.toInstant()
