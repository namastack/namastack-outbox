package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxConfigurationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JdbcTableNameResolverTest {
    @Nested
    inner class DefaultResolution {
        @Test
        fun `should return base table name when no prefix and no schema configured`() {
            val resolver = DefaultJdbcTableNameResolver(JdbcOutboxConfigurationProperties())

            assertThat(resolver.outboxRecord).isEqualTo("outbox_record")
            assertThat(resolver.outboxInstance).isEqualTo("outbox_instance")
            assertThat(resolver.outboxPartitionAssignment).isEqualTo("outbox_partition")
        }

        @Test
        fun `should apply table prefix when configured`() {
            val properties = JdbcOutboxConfigurationProperties(tablePrefix = "my_")
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("my_outbox_record")
        }

        @Test
        fun `should apply schema name when configured`() {
            val properties = JdbcOutboxConfigurationProperties(schemaName = "custom_schema")
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("custom_schema.outbox_record")
        }

        @Test
        fun `should apply both schema and prefix when configured`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "app_",
                    schemaName = "myschema",
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("myschema.app_outbox_record")
            assertThat(resolver.outboxInstance).isEqualTo("myschema.app_outbox_instance")
            assertThat(resolver.outboxPartitionAssignment).isEqualTo("myschema.app_outbox_partition")
        }

        @Test
        fun `precomputed names should be lazily evaluated`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "lazy_",
                    schemaName = "schema",
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            val first = resolver.outboxRecord
            val second = resolver.outboxRecord

            assertThat(first).isSameAs(second)
        }
    }

    @Nested
    inner class CustomTableNames {
        @Test
        fun `should use configured base table names`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tableNames =
                        JdbcOutboxConfigurationProperties.TableNames(
                            record = "OUTBOX_RECORD",
                            instance = "OUTBOX_INSTANCE",
                            partition = "OUTBOX_PARTITION",
                        ),
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("OUTBOX_RECORD")
            assertThat(resolver.outboxInstance).isEqualTo("OUTBOX_INSTANCE")
            assertThat(resolver.outboxPartitionAssignment).isEqualTo("OUTBOX_PARTITION")
        }

        @Test
        fun `should combine prefix and schema with custom base names`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "ACME_",
                    schemaName = "APP",
                    tableNames =
                        JdbcOutboxConfigurationProperties.TableNames(
                            record = "OUTBOX_RECORD",
                            instance = "OUTBOX_INSTANCE",
                            partition = "OUTBOX_PARTITION",
                        ),
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.outboxRecord).isEqualTo("APP.ACME_OUTBOX_RECORD")
            assertThat(resolver.outboxInstance).isEqualTo("APP.ACME_OUTBOX_INSTANCE")
            assertThat(resolver.outboxPartitionAssignment).isEqualTo("APP.ACME_OUTBOX_PARTITION")
        }
    }

    @Nested
    inner class CustomImplementation {
        @Test
        fun `interface can be fully overridden`() {
            val resolver =
                object : JdbcTableNameResolver {
                    override val outboxRecord = "custom_records"
                    override val outboxInstance = "custom_instances"
                    override val outboxPartitionAssignment = "custom_partitions"
                }

            assertThat(resolver.outboxRecord).isEqualTo("custom_records")
            assertThat(resolver.outboxInstance).isEqualTo("custom_instances")
            assertThat(resolver.outboxPartitionAssignment).isEqualTo("custom_partitions")
        }
    }
}
