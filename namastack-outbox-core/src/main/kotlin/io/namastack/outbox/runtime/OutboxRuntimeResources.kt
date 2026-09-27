package io.namastack.outbox.runtime

import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler

/**
 * Threading resources borrowed by one outbox runtime.
 *
 * [taskExecutor] may be dedicated to this runtime or explicitly shared. [taskScheduler] schedules
 * polling and partition rebalancing and must serialize those operations for this runtime.
 * [heartbeatScheduler] must provide execution capacity independently of potentially long processing
 * work. The runtime stops its scheduled tasks but does not close any supplied resource.
 *
 * @property taskExecutor Executor for parallel record processing
 * @property taskScheduler Scheduler for polling and partition rebalancing
 * @property heartbeatScheduler Scheduler for instance heartbeats
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimeResources(
    val taskExecutor: TaskExecutor,
    val taskScheduler: TaskScheduler,
    val heartbeatScheduler: TaskScheduler,
)
