package io.namastack.outbox.config

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.annotation.EnableOutbox
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import java.sql.SQLException
import java.time.Clock
import javax.sql.DataSource

class JpaOutboxSchemaAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaOutboxSchemaAutoConfiguration::class.java))

    @Test
    fun `creates database initializer when schema initialization enabled`() {
        contextRunner
            .withUserConfiguration(ConfigurationWithRealDataSource::class.java)
            .withPropertyValues("outbox.schema-initialization.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(DataSourceScriptDatabaseInitializer::class.java)
            }
    }

    @Test
    fun `does not create database initializer when explicitly disabled`() {
        contextRunner
            .withUserConfiguration(CompleteConfiguration::class.java)
            .withPropertyValues("outbox.schema-initialization.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            }
    }

    @Test
    fun `does not create database initializer by default`() {
        contextRunner
            .withUserConfiguration(CompleteConfiguration::class.java)
            .run { context ->
                assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            }
    }

    @Test
    fun `throws on unknown database type`() {
        val dataSource = mockk<DataSource>()

        every { dataSource.connection } throws SQLException("unknown connection")

        val config = JpaOutboxSchemaAutoConfiguration()

        Assertions
            .assertThatThrownBy {
                config.outboxDataSourceScriptDatabaseInitializer(dataSource)
            }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("Could not detect database name")
    }

    @Test
    fun `fails when DataSource is missing for schema initialization`() {
        contextRunner
            .withUserConfiguration(ConfigurationWithoutDataSource::class.java)
            .withPropertyValues("outbox.schema-initialization.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("DataSource")
            }
    }

    @Test
    fun `does not create any outbox beans when EnableOutbox annotation missing`() {
        contextRunner
            .withUserConfiguration(ConfigurationWithoutEnableOutbox::class.java)
            .withPropertyValues("outbox.schema-initialization.enabled=true")
            .run { context ->
                assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            }
    }

    @EnableOutbox
    @Configuration
    private class CompleteConfiguration {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithRealDataSource {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun dataSource(): DataSource =
            EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @EnableOutbox
    @Configuration
    private class ConfigurationWithoutDataSource {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }

    @Configuration
    private class ConfigurationWithoutEnableOutbox {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory = mockk(relaxed = true)

        @Bean
        fun outboxRecordSerializer(): OutboxPayloadSerializer = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        fun transactionManager(): PlatformTransactionManager = mockk(relaxed = true)
    }
}
