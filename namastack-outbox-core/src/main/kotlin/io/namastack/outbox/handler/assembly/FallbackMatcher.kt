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
 * @since 1.9.0
 */
internal object FallbackMatcher {
    private val log = LoggerFactory.getLogger(FallbackMatcher::class.java)

    /**
     * Matches a fallback declaration to a primary handler declaration.
     *
     * A fallback is compatible when it was discovered through the same declaration mechanism,
     * has a supported fallback signature, and declares the same resolved payload type. If more
     * than one fallback is compatible, the first candidate is returned and a warning is logged.
     *
     * @param handler Primary handler declaration for which to find a fallback
     * @param fallbacks Fallback declarations discovered on the same bean
     * @return The first compatible fallback, or `null` if none is available
     */
    fun match(
        handler: HandlerCandidate,
        fallbacks: List<FallbackCandidate>,
    ): FallbackCandidate? {
        val payloadType = checkNotNull(handler.payloadType)
        val matches =
            fallbacks.filter {
                it.source == handler.source &&
                    HandlerDiscoveryValidator.supportsFallback(it) &&
                    it.payloadType == payloadType
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
