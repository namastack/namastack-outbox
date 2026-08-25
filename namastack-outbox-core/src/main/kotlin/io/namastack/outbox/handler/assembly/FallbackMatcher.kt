package io.namastack.outbox.handler.assembly

import io.namastack.outbox.handler.discovery.FallbackCandidate
import io.namastack.outbox.handler.discovery.HandlerCandidate
import io.namastack.outbox.handler.discovery.HandlerDiscoveryValidator
import org.slf4j.LoggerFactory

/**
 * Selects a fallback compatible with a primary handler declaration.
 *
 * A fallback must use the same discovery mechanism and payload type as the primary handler. If
 * multiple declarations match, the first declaration is selected and a warning is logged.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object FallbackMatcher {
    private val log = LoggerFactory.getLogger(FallbackMatcher::class.java)

    /** Returns the first compatible fallback, warning when the declaration is ambiguous. */
    fun match(
        handler: HandlerCandidate,
        fallbacks: List<FallbackCandidate>,
    ): FallbackCandidate? {
        val payloadType = handler.method.parameterTypes.first()
        val matches =
            fallbacks.filter {
                it.source == handler.source &&
                    HandlerDiscoveryValidator.supportsFallback(it) &&
                    it.method.parameterTypes[0] == payloadType
            }

        if (matches.size > 1) {
            log.warn(
                "Multiple fallback handlers found for payload type {} in {}: {}. Only the first one ({}) will be registered.",
                payloadType.simpleName,
                handler.bean::class.simpleName,
                matches.joinToString { it.method.name },
                matches.first().method.name,
            )
        }

        return matches.firstOrNull()
    }
}
