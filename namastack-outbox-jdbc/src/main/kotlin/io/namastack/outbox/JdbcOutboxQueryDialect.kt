package io.namastack.outbox

/**
 * Query strategy and row-limit syntax for a JDBC database.
 *
 * [JdbcOutboxRecordQueries] applies the eligibility and ordering rules. This type selects
 * the SQL form used to find each key's earliest incomplete record in strict mode.
 * Unknown database products use window queries and JDBC result limits.
 *
 * @param keySelection Strategy for selecting the first incomplete record of each key in strict mode
 * @param limitClause Optional SQL row-limit suffix containing the `:limit` placeholder
 *
 * @author Roland Beisel
 * @since 1.11.0
 */
internal data class JdbcOutboxQueryDialect(
    val keySelection: JdbcOutboxKeySelection = JdbcOutboxKeySelection.WINDOW,
    val limitClause: String? = null,
) {
    companion object {
        /**
         * Resolves query choices from a JDBC database product name.
         *
         * Matching is case-insensitive. Null or unrecognized names retain the generic
         * window strategy without a database-specific SQL limit.
         *
         * @param databaseName Database product name normalized by Spring's JDBC utilities
         * @return Matching query dialect or the generic fallback
         */
        fun from(databaseName: String?): JdbcOutboxQueryDialect =
            when (databaseName?.lowercase()) {
                "postgresql" ->
                    JdbcOutboxQueryDialect(
                        keySelection = JdbcOutboxKeySelection.PREDECESSOR,
                        limitClause = "FETCH FIRST :limit ROWS ONLY",
                    )

                "h2" -> JdbcOutboxQueryDialect(limitClause = "FETCH FIRST :limit ROWS ONLY")

                "mysql" ->
                    JdbcOutboxQueryDialect(
                        keySelection = JdbcOutboxKeySelection.DISTINCT_HEADS,
                        limitClause = "LIMIT :limit",
                    )

                "mariadb" ->
                    JdbcOutboxQueryDialect(
                        keySelection = JdbcOutboxKeySelection.PREDECESSOR,
                        limitClause = "LIMIT :limit",
                    )

                "oracle" ->
                    JdbcOutboxQueryDialect(
                        keySelection = JdbcOutboxKeySelection.PREDECESSOR,
                        limitClause = "FETCH FIRST :limit ROWS ONLY",
                    )

                "microsoft sql server" ->
                    JdbcOutboxQueryDialect(
                        limitClause = "OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY",
                    )

                else -> JdbcOutboxQueryDialect()
            }
    }
}
