package io.namastack.outbox.handler.discovery

import java.lang.reflect.Method

/**
 * Unvalidated fallback declaration found on a bean.
 *
 * @property bean bean that owns the method
 * @property method reflected fallback method
 * @property payloadType resolved payload type, if the declaration has a payload parameter
 * @property source declaration mechanism used to find the method
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal data class FallbackCandidate(
    val bean: Any,
    val method: Method,
    val payloadType: Class<*>?,
    val source: HandlerSource,
)
