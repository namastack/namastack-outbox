package io.namastack.outbox.query

/**
 * Portable queries for databases without a tested SQL dialect.
 *
 * Keeps the original grouped key selection and lets JDBC bound ordered results. This is best-effort
 * compatibility for manually managed schemas; a JDBC driver alone does not guarantee SQL or type
 * compatibility. Known databases use their dedicated dialect instead.
 *
 * @param tableName Resolved name of the record table
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class GenericJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(tableName, { "" }) {
    override val usesJdbcRowLimits: Boolean = true

    override val findStrictRecordKeys: String = findStrictRecordKeysWithoutLimit

    override val findNonStrictRecordKeys: String = findNonStrictRecordKeysWithoutLimit
}
