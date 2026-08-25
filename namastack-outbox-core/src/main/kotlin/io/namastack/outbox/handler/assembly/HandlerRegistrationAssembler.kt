package io.namastack.outbox.handler.assembly

import io.namastack.outbox.handler.discovery.HandlerDeclarations
import io.namastack.outbox.handler.discovery.HandlerDiscoveryValidator
import io.namastack.outbox.handler.identity.OutboxHandlerMethodIdentityResolver
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory

/**
 * Validates discovered declarations and assembles complete, registry-ready registrations.
 *
 * @param retryPolicyRegistry registry used to resolve explicitly configured retry policies
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal class HandlerRegistrationAssembler(
    retryPolicyRegistry: OutboxRetryPolicyRegistry,
) {
    private val log = LoggerFactory.getLogger(HandlerRegistrationAssembler::class.java)
    private val retryPolicyResolver = HandlerRetryPolicyResolver(retryPolicyRegistry)

    /** Converts supported declarations into registrations with identity, fallback, and retry policy. */
    fun assemble(declarations: HandlerDeclarations): List<HandlerRegistration> {
        HandlerDiscoveryValidator.validateRelationships(declarations)

        return declarations.handlers.mapNotNull { candidate ->
            if (!HandlerDiscoveryValidator.supportsHandler(candidate)) {
                log.warn("No handler method supports {} in {}", candidate.method.name, candidate.bean::class.simpleName)
                return@mapNotNull null
            }

            val identity = OutboxHandlerMethodIdentityResolver.resolve(candidate)
            val primary = HandlerMethodFactory.primary(candidate, identity)
            val fallback =
                FallbackMatcher.match(candidate, declarations.fallbacks)?.let {
                    HandlerMethodFactory.fallback(it.bean, it.method)
                }

            HandlerRegistration(
                beanName = candidate.beanName,
                primary = primary,
                fallback = fallback,
                explicitRetryPolicy = retryPolicyResolver.resolve(candidate),
            )
        }
    }
}
