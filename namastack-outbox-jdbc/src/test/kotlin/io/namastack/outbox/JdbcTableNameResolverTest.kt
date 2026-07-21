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

            assertThat(resolver.getOutboxRecord()).isEqualTo("outbox_record")
            assertThat(resolver.getOutboxInstance()).isEqualTo("outbox_instance")
            assertThat(resolver.getOutboxPartitionAssignment()).isEqualTo("outbox_partition")
        }

        @Test
        fun `should apply table prefix when configured`() {
            val properties = JdbcOutboxConfigurationProperties(tablePrefix = "my_")
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.getOutboxRecord()).isEqualTo("my_outbox_record")
        }

        @Test
        fun `should apply schema name when configured`() {
            val properties = JdbcOutboxConfigurationProperties(schemaName = "custom_schema")
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.getOutboxRecord()).isEqualTo("custom_schema.outbox_record")
        }

        @Test
        fun `should apply both schema and prefix when configured`() {
            val properties =
                JdbcOutboxConfigurationProperties(
                    tablePrefix = "app_",
                    schemaName = "myschema",
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.getOutboxRecord()).isEqualTo("myschema.app_outbox_record")
            assertThat(resolver.getOutboxInstance()).isEqualTo("myschema.app_outbox_instance")
            assertThat(resolver.getOutboxPartitionAssignment()).isEqualTo("myschema.app_outbox_partition")
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

            assertThat(resolver.getOutboxRecord()).isEqualTo("OUTBOX_RECORD")
            assertThat(resolver.getOutboxInstance()).isEqualTo("OUTBOX_INSTANCE")
            assertThat(resolver.getOutboxPartitionAssignment()).isEqualTo("OUTBOX_PARTITION")
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

            assertThat(resolver.getOutboxRecord()).isEqualTo("APP.ACME_OUTBOX_RECORD")
            assertThat(resolver.getOutboxInstance()).isEqualTo("APP.ACME_OUTBOX_INSTANCE")
            assertThat(resolver.getOutboxPartitionAssignment()).isEqualTo("APP.ACME_OUTBOX_PARTITION")
        }
    }

    @Nested
    inner class CustomImplementation {
        @Test
        fun `interface can be fully overridden`() {
            val resolver =
                object : JdbcTableNameResolver {
                    override fun getOutboxRecord() = "custom_records"

                    override fun getOutboxInstance() = "custom_instances"

                    override fun getOutboxPartitionAssignment() = "custom_partitions"
                }

            assertThat(resolver.getOutboxRecord()).isEqualTo("custom_records")
            assertThat(resolver.getOutboxInstance()).isEqualTo("custom_instances")
            assertThat(resolver.getOutboxPartitionAssignment()).isEqualTo("custom_partitions")
        }
    }
}
