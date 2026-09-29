package io.namastack.outbox.runtime

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import java.time.Clock

/**
 * Fully resolved inputs for one outbox runtime.
 *
 * The caller resolves persistence, handler infrastructure, context collection, threading, time,
 * and observation before invoking [OutboxRuntimeFactory]. The factory does not discover bean names,
 * select persistence, or coordinate this specification with other runtimes.
 *
 * @property settings Runtime-local processing and lifecycle settings
 * @property persistence Resolved persistence repositories
 * @property handlerInfrastructure Isolated handler registration and invocation state
 * @property contextCollector Collector for context added during scheduling
 * @property resources Executor and schedulers used by the runtime
 * @property clock Clock used throughout the runtime
 * @property observationRegistry Registry used for scheduled-task observations
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimeSpec(
    val settings: OutboxRuntimeSettings = OutboxRuntimeSettings(),
    val persistence: OutboxRuntimePersistence,
    val handlerInfrastructure: OutboxHandlerInfrastructure,
    val contextCollector: OutboxContextCollector,
    val resources: OutboxRuntimeResources,
    val clock: Clock,
    val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
)
