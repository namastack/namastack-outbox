package io.namastack.outbox.handler.registry

import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod

/**
 * Describes the declaration that owns an ID in the shared routing namespace.
 *
 * Canonical IDs and aliases occupy the same namespace. Keeping the route role, bean name, and
 * handler together makes startup collision errors identify both conflicting declarations.
 *
 * @property role whether the route is a canonical ID or an alias
 * @property beanName Spring bean that contributed the route
 * @property handler primary handler reached through the route
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal data class RoutingOwner(
    val role: String,
    val beanName: String,
    val handler: OutboxHandlerMethod,
)
