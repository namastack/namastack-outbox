package io.namastack.outbox.config

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRuntimeModeProvider
import org.springframework.beans.factory.InitializingBean

/**
 * Validates that exactly one provider supports the selected outbox runtime mode.
 *
 * @param properties Bound outbox configuration containing the selected mode
 * @param providers Available runtime mode providers
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
internal class OutboxRuntimeModeValidator(
    private val properties: OutboxProperties,
    private val providers: List<OutboxRuntimeModeProvider>,
) : InitializingBean {
    /**
     * Validates runtime mode support during application context initialization.
     *
     * @throws IllegalStateException if the selected mode has no provider or multiple providers
     */
    override fun afterPropertiesSet() {
        val mode = properties.mode
        val matchingProviders = providers.filter { provider -> provider.getMode() == mode }

        check(matchingProviders.isNotEmpty()) {
            "Outbox runtime mode '${mode.name.lowercase()}' is not supported. " +
                "Add a module that supports this mode or set 'namastack.outbox.mode=single'."
        }
        check(matchingProviders.size == 1) {
            "Outbox runtime mode '${mode.name.lowercase()}' has ${matchingProviders.size} providers; " +
                "exactly one OutboxRuntimeModeProvider must support the selected mode."
        }
    }
}
