package io.namastack.outbox.runtime

import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler

/**
 * Threading resources borrowed by one outbox runtime.
 *
 * [taskExecutor] may be dedicated to this runtime or explicitly shared. [taskScheduler] schedules
 * both polling and partition rebalancing and must serialize those tasks for this runtime.
 * [heartbeatScheduler] is separate so processing cannot delay heartbeats and may be shared.
 * The runtime stops its scheduled tasks but does not close any supplied resource.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimeResources(
    val taskExecutor: TaskExecutor,
    val taskScheduler: TaskScheduler,
    val heartbeatScheduler: TaskScheduler,
)
