package io.namastack.outbox.handler.assembly

import io.namastack.outbox.handler.discovery.HandlerDeclarations
import io.namastack.outbox.handler.discovery.HandlerDiscoveryValidator
import io.namastack.outbox.handler.identity.OutboxHandlerMethodIdentityResolver
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Validates discovered declarations and assembles complete, registry-ready registrations.
 *
 * @param retryPolicyRegistry registry used to resolve explicitly configured retry policies
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal class HandlerRegistrationAssembler(
    retryPolicyRegistry: OutboxRetryPolicyRegistry,
) {
    private val log = LoggerFactory.getLogger(HandlerRegistrationAssembler::class.java)
    private val retryPolicyResolver = HandlerRetryPolicyResolver(retryPolicyRegistry)

    /**
     * Validates and converts discovered declarations into complete handler registrations.
     *
     * Unsupported annotated signatures are skipped. Every supported primary declaration receives
     * a routing identity and is paired with a compatible fallback and explicit retry policy when
     * present.
     *
     * @param declarations Primary and fallback declarations discovered on one bean
     * @param primaryMethodPredicate Predicate selecting the primary methods to assemble after all
     * declaration relationships have been validated
     * @return Complete registrations ready to be installed in the handler registry
     * @throws IllegalStateException if the declarations contain an ambiguous relationship
     */
    fun assemble(
        declarations: HandlerDeclarations,
        primaryMethodPredicate: (Method) -> Boolean = { true },
    ): List<HandlerRegistration> {
        HandlerDiscoveryValidator.validateRelationships(declarations)

        return declarations.handlers
            .filter { primaryMethodPredicate(it.method) }
            .mapNotNull { candidate ->
                if (!HandlerDiscoveryValidator.supportsHandler(candidate)) {
                    log.warn(
                        "No handler method supports {} in {}",
                        candidate.method.name,
                        candidate.bean::class.simpleName,
                    )
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
