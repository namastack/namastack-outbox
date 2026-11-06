package io.namastack.outbox.config

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.DatabaseMetaData
import javax.sql.DataSource

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
    inner class OracleTest {
        @Test
        fun `has correct schema location`() {
            assertThat(
                JdbcDatabaseType.Oracle.schemaLocation,
            ).isEqualTo("classpath:schema/oracle/outbox-tables.sql")
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
        fun `returns Oracle type for oracle input`() {
            assertThat(JdbcDatabaseType.from("oracle")).isEqualTo(JdbcDatabaseType.Oracle)
            assertThat(JdbcDatabaseType.from("ORACLE")).isEqualTo(JdbcDatabaseType.Oracle)
            assertThat(JdbcDatabaseType.from("Oracle")).isEqualTo(JdbcDatabaseType.Oracle)
        }

        @Test
        fun `throws for unsupported database`() {
            Assertions
                .assertThatThrownBy { JdbcDatabaseType.from("unsupported") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Unsupported database type: unsupported")
        }
    }

    /**
     * Tests database type detection from datasource metadata.
     *
     * @author Aleksander Zamojski
     */
    @Nested
    inner class DetectTest {
        @Test
        fun `detects the database type from the datasource metadata`() {
            val dataSource =
                JdbcDataSource().apply {
                    setURL("jdbc:h2:mem:jdbc-database-type;DB_CLOSE_DELAY=-1")
                    user = "sa"
                }

            assertThat(JdbcDatabaseType.detect(dataSource)).isEqualTo(JdbcDatabaseType.H2)
        }

        @ParameterizedTest
        @ValueSource(strings = ["SQLite", "Apache Derby"])
        fun `unsupported products resolve to no database type`(productName: String) {
            val metadata = mockk<DatabaseMetaData> { every { databaseProductName } returns productName }
            val connection = mockk<Connection>(relaxed = true) { every { metaData } returns metadata }
            val dataSource = mockk<DataSource> { every { getConnection() } returns connection }

            assertThat(JdbcDatabaseType.detect(dataSource)).isNull()
        }
    }
}
