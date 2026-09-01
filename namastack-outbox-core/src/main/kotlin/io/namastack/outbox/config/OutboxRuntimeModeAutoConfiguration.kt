package io.namastack.outbox.config

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRuntimeMode.SINGLE
import io.namastack.outbox.OutboxRuntimeModeProvider
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Configures and validates outbox runtime mode selection.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
@AutoConfiguration(before = [OutboxCoreInfrastructureAutoConfiguration::class])
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxRuntimeModeAutoConfiguration {
    /**
     * Contributes support for the standard single-runtime mode.
     *
     * @return Single-runtime mode provider
     */
    @Bean
    fun singleOutboxRuntimeModeProvider(): OutboxRuntimeModeProvider = OutboxRuntimeModeProvider { SINGLE }

    /**
     * Creates runtime mode validation for the selected mode.
     *
     * @param properties Bound outbox configuration
     * @param providers Available runtime mode providers
     * @return Runtime mode validator
     */
    @Bean
    fun outboxRuntimeModeValidator(
        properties: OutboxProperties,
        providers: List<OutboxRuntimeModeProvider>,
    ): InitializingBean = OutboxRuntimeModeValidator(properties, providers)
}
