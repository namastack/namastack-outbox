package io.namastack.outbox.runtime

import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler

/**
 * Fully resolved threading resources for one programmatic outbox runtime.
 *
 * The normal scheduler is shared by processing and partition rebalancing. The heartbeat scheduler
 * remains separate so a busy processing scheduler cannot delay instance liveness updates. Entries
 * in [ownedResources] are closed in reverse order; borrowed parent resources must not be included.
 *
 * @property taskExecutor Executor for parallel record processing
 * @property taskScheduler Scheduler for polling and partition rebalancing
 * @property heartbeatScheduler Scheduler for instance heartbeats
 * @property ownedResources Threading resources owned exclusively by the runtime
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimeResources(
    val taskExecutor: TaskExecutor,
    val taskScheduler: TaskScheduler,
    val heartbeatScheduler: TaskScheduler,
    val ownedResources: List<AutoCloseable> = emptyList(),
)
