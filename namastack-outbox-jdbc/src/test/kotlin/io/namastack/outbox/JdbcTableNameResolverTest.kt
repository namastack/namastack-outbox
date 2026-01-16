package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JdbcTableNameResolverTest {
    @Nested
    inner class ResolveMethod {
        @Test
        fun `should return base table name when no prefix and no schema configured`() {
            val properties = JdbcOutboxConfigurationProperties()
            val resolver = JdbcTableNameResolver(properties)

            val result = resolver.resolve("outbox_record")

            assertThat(result).isEqualTo("outbox_record")
        }

        @Test
        fun `should apply table prefix when configured`() {
            val properties = JdbcOutboxConfigurationProperties(tablePrefix = "my_")
            val resolver = JdbcTableNameResolver(properties)

            val result = resolver.resolve("outbox_record")

            assertThat(result).isEqualTo("my_outbox_record")
        }

        @Test
        fun `should apply schema name when configured`() {
            val properties = JdbcOutboxConfigurationProperties(schemaName = "custom_schema")
            val resolver = JdbcTableNameResolver(properties)

            val result = resolver.resolve("outbox_record")

            assertThat(result).isEqualTo("custom_schema.outbox_record")
        }

        @Test
        fun `should apply both schema and prefix when configured`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "app_",
                    schemaName = "myschema",
                )
            val resolver = JdbcTableNameResolver(properties)

            val result = resolver.resolve("outbox_record")

            assertThat(result).isEqualTo("myschema.app_outbox_record")
        }

        @Test
        fun `should handle empty table prefix`() {
            val properties = JdbcOutboxConfigurationProperties(tablePrefix = "")
            val resolver = JdbcTableNameResolver(properties)

            val result = resolver.resolve("outbox_instance")

            assertThat(result).isEqualTo("outbox_instance")
        }

        @Test
        fun `should handle null schema name`() {
            val properties = JdbcOutboxConfigurationProperties(schemaName = null)
            val resolver = JdbcTableNameResolver(properties)

            val result = resolver.resolve("outbox_partition")

            assertThat(result).isEqualTo("outbox_partition")
        }
    }

    @Nested
    inner class PrecomputedTableNames {
        @Test
        fun `outboxRecord should resolve correctly with defaults`() {
            val properties = JdbcOutboxConfigurationProperties()
            val resolver = JdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("outbox_record")
        }

        @Test
        fun `outboxInstance should resolve correctly with defaults`() {
            val properties = JdbcOutboxConfigurationProperties()
            val resolver = JdbcTableNameResolver(properties)

            assertThat(resolver.outboxInstance).isEqualTo("outbox_instance")
        }

        @Test
        fun `outboxPartitionAssignment should resolve correctly with defaults`() {
            val properties = JdbcOutboxConfigurationProperties()
            val resolver = JdbcTableNameResolver(properties)

            assertThat(resolver.outboxPartitionAssignment).isEqualTo("outbox_partition")
        }

        @Test
        fun `all precomputed names should apply prefix and schema`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "test_",
                    schemaName = "prod",
                )
            val resolver = JdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("prod.test_outbox_record")
            assertThat(resolver.outboxInstance).isEqualTo("prod.test_outbox_instance")
            assertThat(resolver.outboxPartitionAssignment).isEqualTo("prod.test_outbox_partition")
        }

        @Test
        fun `precomputed names should be lazily evaluated`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "lazy_",
                    schemaName = "schema",
                )
            val resolver = JdbcTableNameResolver(properties)

            // Access multiple times to verify lazy evaluation returns same result
            val first = resolver.outboxRecord
            val second = resolver.outboxRecord

            assertThat(first).isSameAs(second)
        }
    }
}
