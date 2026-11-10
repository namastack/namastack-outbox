package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.config.OutboxCoreMulticasterAutoConfiguration
import io.namastack.outbox.config.OutboxCoreProcessingAutoConfiguration
import io.namastack.outbox.config.OutboxCoreSchedulingAutoConfiguration
import io.namastack.outbox.config.OutboxCoreThreadingAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD

/**
 * Annotation for integration tests that use JDBC persistence and transactions with the Outbox framework.
 *
 * Provides common test configuration:
 * - JDBC testing with JdbcTest
 * - Automatic context reloading with DirtiesContext
 * - Auto-configuration for Outbox Core, JDBC persistence, and Jackson serialization
 * - Test processor component for handling records
 * - Outbox properties configuration
 * - Configured DataSource usage (H2 by default)
 *
 * Apply this annotation to your integration test classes to inherit all configurations.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@JdbcTest
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
@ImportAutoConfiguration(
    TaskExecutionAutoConfiguration::class,
    TaskSchedulingAutoConfiguration::class,
    OutboxCoreInfrastructureAutoConfiguration::class,
    OutboxCoreThreadingAutoConfiguration::class,
    OutboxCoreProcessingAutoConfiguration::class,
    OutboxCoreSchedulingAutoConfiguration::class,
    OutboxCoreMulticasterAutoConfiguration::class,
    JdbcOutboxAutoConfiguration::class,
    JdbcOutboxSchemaAutoConfiguration::class,
    OutboxJacksonAutoConfiguration::class,
)
@EnableConfigurationProperties(OutboxProperties::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
annotation class OutboxIntegrationTest
