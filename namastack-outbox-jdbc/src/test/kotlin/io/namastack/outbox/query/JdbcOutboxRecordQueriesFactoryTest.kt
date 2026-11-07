package io.namastack.outbox.query

import io.namastack.outbox.config.JdbcDatabaseType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdbcOutboxRecordQueriesFactoryTest {
    private val table = "outbox_record"

    @Test
    fun `selects the statements of each database type`() {
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.PostgreSQL, table))
            .isInstanceOf(PostgresJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.MySQL, table))
            .isInstanceOf(MySqlJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.MariaDB, table))
            .isInstanceOf(MariaDbJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.SQLServer, table))
            .isInstanceOf(SqlServerJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.Oracle, table))
            .isInstanceOf(OracleJdbcOutboxRecordQueries::class.java)
        assertThat(JdbcOutboxRecordQueriesFactory.create(JdbcDatabaseType.H2, table))
            .isInstanceOf(H2JdbcOutboxRecordQueries::class.java)
    }

    @Test
    fun `selects the portable statements for an unknown database type`() {
        assertThat(JdbcOutboxRecordQueriesFactory.create(null, table).javaClass)
            .isEqualTo(DefaultJdbcOutboxRecordQueries::class.java)
    }
}
