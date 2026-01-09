package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JdbcDatabaseTypeTest {
    @Nested
    inner class PostgreSQLTest {
        @Test
        fun `has correct schema location`() {
            assertThat(
                JdbcDatabaseType.PostgreSQL.schemaLocation,
            ).isEqualTo("classpath:schema/postgres/outbox-tables.sql")
        }
    }

    @Nested
    inner class MySQLTest {
        @Test
        fun `has correct schema location`() {
            assertThat(JdbcDatabaseType.MySQL.schemaLocation).isEqualTo("classpath:schema/mysql/outbox-tables.sql")
        }
    }

    @Nested
    inner class H2Test {
        @Test
        fun `has correct schema location`() {
            assertThat(JdbcDatabaseType.H2.schemaLocation).isEqualTo("classpath:schema/h2/outbox-tables.sql")
        }
    }

    @Nested
    inner class MariaDBTest {
        @Test
        fun `has correct schema location`() {
            assertThat(JdbcDatabaseType.MariaDB.schemaLocation).isEqualTo("classpath:schema/mariadb/outbox-tables.sql")
        }
    }

    @Nested
    inner class SQLServerTest {
        @Test
        fun `has correct schema location`() {
            assertThat(
                JdbcDatabaseType.SQLServer.schemaLocation,
            ).isEqualTo("classpath:schema/sqlserver/outbox-tables.sql")
        }
    }

    @Nested
    inner class FromTest {
        @Test
        fun `returns PostgreSQL type for postgresql input`() {
            assertThat(JdbcDatabaseType.from("postgresql")).isEqualTo(JdbcDatabaseType.PostgreSQL)
            assertThat(JdbcDatabaseType.from("POSTGRESQL")).isEqualTo(JdbcDatabaseType.PostgreSQL)
            assertThat(JdbcDatabaseType.from("PostgreSQL")).isEqualTo(JdbcDatabaseType.PostgreSQL)
        }

        @Test
        fun `returns MySQL type for mysql input`() {
            assertThat(JdbcDatabaseType.from("mysql")).isEqualTo(JdbcDatabaseType.MySQL)
            assertThat(JdbcDatabaseType.from("MYSQL")).isEqualTo(JdbcDatabaseType.MySQL)
            assertThat(JdbcDatabaseType.from("MySQL")).isEqualTo(JdbcDatabaseType.MySQL)
        }

        @Test
        fun `returns H2 type for h2 input`() {
            assertThat(JdbcDatabaseType.from("h2")).isEqualTo(JdbcDatabaseType.H2)
            assertThat(JdbcDatabaseType.from("H2")).isEqualTo(JdbcDatabaseType.H2)
        }

        @Test
        fun `returns MariaDB type for mariadb input`() {
            assertThat(JdbcDatabaseType.from("mariadb")).isEqualTo(JdbcDatabaseType.MariaDB)
            assertThat(JdbcDatabaseType.from("MARIADB")).isEqualTo(JdbcDatabaseType.MariaDB)
            assertThat(JdbcDatabaseType.from("MariaDB")).isEqualTo(JdbcDatabaseType.MariaDB)
        }

        @Test
        fun `returns SQLServer type for microsoft sql server input`() {
            assertThat(JdbcDatabaseType.from("microsoft sql server")).isEqualTo(JdbcDatabaseType.SQLServer)
            assertThat(JdbcDatabaseType.from("MICROSOFT SQL SERVER")).isEqualTo(JdbcDatabaseType.SQLServer)
            assertThat(JdbcDatabaseType.from("Microsoft SQL Server")).isEqualTo(JdbcDatabaseType.SQLServer)
        }

        @Test
        fun `throws for unsupported database`() {
            assertThatThrownBy { JdbcDatabaseType.from("unsupported") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Unsupported database type: unsupported")
        }
    }
}
