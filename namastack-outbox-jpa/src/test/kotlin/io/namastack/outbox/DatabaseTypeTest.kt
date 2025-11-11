package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DatabaseTypeTest {
    @Nested
    inner class PostgreSQLTest {
        @Test
        fun `has correct schema location`() {
            assertThat(DatabaseType.PostgreSQL.schemaLocation).isEqualTo("classpath:schema/postgres/outbox-tables.sql")
        }
    }

    @Nested
    inner class MySQLTest {
        @Test
        fun `has correct schema location`() {
            assertThat(DatabaseType.MySQL.schemaLocation).isEqualTo("classpath:schema/mysql/outbox-tables.sql")
        }
    }

    @Nested
    inner class H2Test {
        @Test
        fun `has correct schema location`() {
            assertThat(DatabaseType.H2.schemaLocation).isEqualTo("classpath:schema/h2/outbox-tables.sql")
        }
    }

    @Nested
    inner class FromTest {
        @Test
        fun `returns PostgreSQL type for postgresql input`() {
            assertThat(DatabaseType.from("postgresql")).isEqualTo(DatabaseType.PostgreSQL)
            assertThat(DatabaseType.from("POSTGRESQL")).isEqualTo(DatabaseType.PostgreSQL)
            assertThat(DatabaseType.from("PostgreSQL")).isEqualTo(DatabaseType.PostgreSQL)
        }

        @Test
        fun `returns MySQL type for mysql input`() {
            assertThat(DatabaseType.from("mysql")).isEqualTo(DatabaseType.MySQL)
            assertThat(DatabaseType.from("MYSQL")).isEqualTo(DatabaseType.MySQL)
            assertThat(DatabaseType.from("MySQL")).isEqualTo(DatabaseType.MySQL)
        }

        @Test
        fun `returns H2 type for h2 input`() {
            assertThat(DatabaseType.from("h2")).isEqualTo(DatabaseType.H2)
            assertThat(DatabaseType.from("H2")).isEqualTo(DatabaseType.H2)
        }

        @Test
        fun `throws for unsupported database`() {
            assertThatThrownBy { DatabaseType.from("unsupported") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Unsupported database type: unsupported")
        }
    }
}
