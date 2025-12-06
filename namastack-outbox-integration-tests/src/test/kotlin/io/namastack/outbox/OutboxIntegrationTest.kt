package io.namastack.outbox

import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.annotation.DirtiesContext

/**
 * Annotation for integration tests that use JPA with the Outbox framework.
 *
 * Provides common test configuration:
 * - JPA testing with DataJpaTest
 * - Automatic context reloading with DirtiesContext
 * - Auto-configuration for Jackson, Outbox Core, JPA, and Jackson modules
 * - Test processor component for handling records
 * - Outbox properties configuration
 * - Real database usage (not in-memory)
 *
 * Apply this annotation to your integration test classes to inherit all configurations.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest(showSql = false)
@DirtiesContext
@ImportAutoConfiguration(
    OutboxCoreAutoConfiguration::class,
    JpaOutboxAutoConfiguration::class,
    OutboxJacksonAutoConfiguration::class,
)
@EnableConfigurationProperties(OutboxProperties::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
annotation class OutboxIntegrationTest
