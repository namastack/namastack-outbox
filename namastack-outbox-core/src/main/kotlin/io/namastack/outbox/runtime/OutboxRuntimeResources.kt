package io.namastack.outbox.runtime

import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler

/**
 * Fully resolved threading resources for one programmatic outbox runtime.
 *
 * Processing and partition rebalancing may share a scheduler because the runtime serializes their
 * access to partition assignments. The heartbeat scheduler must have execution capacity independent
 * of potentially long processing batches. It may be the same scheduler instance only when its pool
 * provides that capacity. Entries in [ownedResources] are closed in reverse order; borrowed parent
 * resources must not be included.
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
