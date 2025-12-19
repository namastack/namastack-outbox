package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.handler.OutboxHandlerInvoker
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.util.concurrent.CountDownLatch

/**
 * Scheduler for processing outbox records with partition-aware distribution.
 *
 * Orchestrates the processing of outbox records across a distributed system by:
 * - Coordinating with PartitionCoordinator to process only assigned partitions
 * - Loading record keys in batches from assigned partitions
 * - Processing records in parallel (per key) while maintaining order within each key
 * - Implementing handler-specific retry logic with configurable backoff strategies
 * - Handling failures gracefully with optional deletion of completed records
 *
 * ## Concurrency Model
 *
 * - **Across Keys**: Multiple record keys processed in parallel via TaskExecutor
 * - **Within Key**: Records for same key processed sequentially (strict ordering)
 * - **Partitions**: Each partition assigned to single instance (no concurrent access)
 *
 * ## Processing Guarantees
 *
 * - **At-least-once**: Records may be processed multiple times (idempotent handlers required)
 * - **Ordering per Key**: Records with same key processed in creation order
 * - **Partition Safety**: No instance processes same partition concurrently
 * - **Failure Resilience**: Failures trigger retries with handler-specific policies
 *
 * ## Handler-Specific Retry Policies
 *
 * Each handler can define its own retry policy via:
 * - @OutboxRetryable annotation on the handler method
 * - OutboxRetryAware interface implementation
 * - Default policy from configuration as fallback
 *
 * Policies are resolved during handler registration at application startup and
 * stored in the retry policy registry for efficient runtime lookup.
 *
 * @param recordRepository Repository for accessing and updating outbox records
 * @param handlerInvoker Handler dispatcher that invokes appropriate handlers
 * @param partitionCoordinator Coordinator that manages partition assignments
 * @param taskExecutor TaskExecutor for parallel processing of record keys
 * @param retryPolicyRegistry Registry for handler-specific retry policies
 * @param properties Configuration properties (batch size, retry config, etc.)
 * @param clock Clock for timestamp generation and timeout calculations
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxProcessingScheduler(
    private val recordRepository: OutboxRecordRepository,
    private val handlerInvoker: OutboxHandlerInvoker,
    private val partitionCoordinator: PartitionCoordinator,
    private val taskExecutor: TaskExecutor,
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    /**
     * Scheduled processing cycle triggered by Spring's scheduler.
     *
     * Runs periodically (configured via outbox.poll-interval, default 2000ms).
     * Uses @Scheduled to integrate with Spring's task scheduling framework.
     *
     * ## Processing Algorithm
     *
     * 1. **Partition Discovery**: Get currently assigned partitions from coordinator
     *    - Early return if no partitions assigned (instance starting/stopping)
     * 2. **Batch Loading**: Load up to batchSize distinct record keys with NEW records
     *    - Honors [stopOnFirstFailure] setting (may skip keys with previous failures)
     * 3. **Parallel Dispatch**: Submit each record key to TaskExecutor as separate task
     * 4. **Synchronization**: Wait for all tasks to complete using CountDownLatch
     *    - Ensures one processing cycle finishes before next begins
     * 5. **Error Handling**: Catches and logs any exceptions without propagating
     *
     * ## CountDownLatch Pattern
     *
     * Used to synchronize batch completion:
     * - Main thread creates latch with count = number of record keys
     * - Each key processing task decrements latch on completion (finally block)
     * - Main thread blocks on latch.await() until all tasks finish
     * - Prevents next scheduling cycle until current batch is done
     *
     * Benefits:
     * - Prevents concurrent batches (ordered processing)
     * - Handles both successful and failed task completions
     * - Timeout can be added if needed (currently awaits indefinitely)
     *
     * ## Logging Levels
     *
     * - DEBUG: Entry point, partition assignment, batch discovery
     * - TRACE: Individual record key processing status
     * - ERROR: Unexpected exceptions during processing
     *
     * @throws Exception caught internally and logged (never propagated to scheduler)
     */
    @Scheduled(fixedDelayString = "\${outbox.poll-interval:2000}", scheduler = "outboxDefaultScheduler")
    fun process() {
        try {
            val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
            if (assignedPartitions.isEmpty()) return

            log.debug(
                "Processing {} partitions: {}",
                assignedPartitions.size,
                assignedPartitions.sorted(),
            )

            val recordKeys =
                recordRepository.findRecordKeysInPartitions(
                    partitions = assignedPartitions,
                    status = NEW,
                    batchSize = properties.batchSize,
                    ignoreRecordKeysWithPreviousFailure = properties.processing.stopOnFirstFailure,
                )

            if (recordKeys.isNotEmpty()) {
                log.debug("Found {} record keys to process", recordKeys.size)

                // Synchronization mechanism for batch processing
                val latch = CountDownLatch(recordKeys.size)
                recordKeys.forEach { recordKey ->
                    taskExecutor.execute {
                        try {
                            processRecordKey(recordKey)
                        } finally {
                            // Decrement latch regardless of success/failure
                            // Ensures main thread is notified even if task throws
                            latch.countDown()
                        }
                    }
                }

                // Block main thread until all record keys are processed
                // Prevents overlapping batches
                latch.await()
                log.debug("Finished processing {} record keys", recordKeys.size)
            }
        } catch (ex: Exception) {
            log.error("Error during partition-aware outbox processing", ex)
        }
    }

    /**
     * Processes all incomplete records for a single record key.
     *
     * Records with the same key are processed sequentially in creation order.
     * Processing stops early under two conditions:
     * 1. **Future Retry Time**: Current record not yet ready (maintains ordering gap)
     * 2. **Early Failure Exit**: Previous record failed and stopOnFirstFailure=true
     *
     * ## Sequential Processing
     *
     * All records in a key are processed by a single thread (via forEach loop).
     * This guarantees strict FIFO ordering, which is critical for event sourcing
     * and ordered state machines where event order matters.
     *
     * ## Retry Delay Awareness
     *
     * Records with future retry times (due to exponential backoff) are skipped
     * but not removed from the sequence. The next cycle will retry them later.
     * This maintains the logical ordering even with delayed retries.
     *
     * Example with 3 records and stopOnFirstFailure=true:
     * ```
     * Record 1: SUCCESS -> mark completed, continue
     * Record 2: FAILED -> increment failure count, schedule retry (next.retryTime = now + 10s)
     * Record 3: SKIPPED -> not processed because stopOnFirstFailure is true
     * ```
     *
     * ## Exception Handling
     *
     * Catches exceptions at key level to prevent one key's failures from
     * affecting other keys. Logged as ERROR but processing continues.
     *
     * @param recordKey The logical key identifying records to process
     */
    private fun processRecordKey(recordKey: String) {
        try {
            val records = recordRepository.findIncompleteRecordsByRecordKey(recordKey)

            if (records.isEmpty()) {
                return
            }

            log.trace("Processing {} records for record key {}", records.size, recordKey)

            for (record in records) {
                if (!record.canBeRetried(clock)) {
                    log.trace("Skipping record {} - not ready for retry (nextRetryTime in future)", record.id)
                    break
                }

                val success = processRecord(record)

                if (!success && properties.processing.stopOnFirstFailure) {
                    log.trace(
                        "Stopping record key {} processing due to failure (stopOnFirstFailure=true)",
                        recordKey,
                    )
                    break
                }
            }
        } catch (ex: Exception) {
            log.error("Error processing record key {}", recordKey, ex)
        }
    }

    /**
     * Processes a single record through its registered handler.
     *
     * ## Processing Steps
     *
     * 1. **Handler Invocation**: Dispatch record to appropriate handler via dispatcher
     *    - Handler determined by record's handlerId stored during scheduling
     *    - Includes full metadata context (key, handlerId, createdAt)
     * 2. **Success Path**: Record successfully processed
     *    a. If deleteCompletedRecords=true: Delete from database (cleanup)
     *    b. Otherwise: Mark as COMPLETED and persist updated state
     * 3. **Failure Path**: Handler threw exception
     *    a. Delegate to handleFailure() for retry logic
     *    b. Return false to signal failure to caller
     *
     * ## Handler ID Resolution
     *
     * The record contains a handlerId that was assigned when the record was scheduled.
     * This ID uniquely identifies which handler method should process it.
     * Dispatcher looks up handler by ID and invokes with correct parameters.
     *
     * ## Return Value
     *
     * Returns boolean indicating success (true) or failure (false).
     * Used by caller to decide whether to continue processing subsequent records
     * in same key (stopOnFirstFailure logic).
     *
     * @param record The outbox record to process
     * @return true if record processed successfully, false if handler threw exception
     */
    private fun processRecord(record: OutboxRecord<*>): Boolean =
        try {
            log.trace(
                "Processing record {}  (key: {}, partition: {})",
                record.id,
                record.key,
                record.partition,
            )

            // Create metadata context for handler
            val metadata = OutboxRecordMetadata(record.key, record.handlerId, record.createdAt)

            // Dispatch to appropriate handler based on handlerId
            handlerInvoker.dispatch(record.payload, metadata)

            // Post-processing: delete or mark completed
            if (properties.processing.deleteCompletedRecords) {
                log.trace(
                    "Deleting outbox record {} after successful processing (deleteCompletedRecords=true)",
                    record.id,
                )
                recordRepository.deleteById(record.id)
            } else {
                log.trace("Marking outbox record {} as COMPLETED", record.id)
                record.markCompleted(clock)
                recordRepository.save(record)
            }

            log.trace("Successfully processed record {} for key {}", record.id, record.key)
            true
        } catch (ex: Exception) {
            handleFailure(record, ex)
            false
        }

    /**
     * Handles failure of a record and decides on retry strategy.
     *
     * ## Failure Handling Logic
     *
     * 1. **Increment Counter**: Increment failureCount to track attempts
     * 2. **Policy Resolution**: Load handler-specific retry policy from registry
     *    - Each handler has its own policy resolved during registration
     *    - Policies defined via @OutboxRetryable, OutboxRetryAware, or default
     * 3. **Exhaustion Check**: Check if retries are exhausted
     *    - maxRetries defines max number of attempts (default 3)
     *    - failureCount >= maxRetries means no more retries
     * 4. **Policy Check**: Ask handler's retry policy if exception is retryable
     *    - Some exceptions (e.g., validation errors) should not be retried
     *    - Policy can implement custom logic (e.g., only retry transient errors)
     * 5. **Mark Failed**: If exhausted or non-retryable
     *    - Set status to FAILED
     *    - Log warning with attempt count
     * 6. **Schedule Retry**: If retries remaining and retryable
     *    - Calculate delay using handler's retryPolicy.nextDelay(failureCount)
     *    - Update nextRetryTime on record
     *    - Log debug message with retry number and delay
     * 7. **Persist**: Save updated record state to database
     *
     * ## Handler-Specific Retry Policies
     *
     * Different handlers can have different retry behaviors:
     * - Critical payment handlers: aggressive retries (1s, 3s, 9s...)
     * - Notification handlers: gentle retries (1m, 2m, 4m...)
     * - Audit handlers: custom exception-based logic
     *
     * ## Retry Delay Calculation
     *
     * Delays are calculated based on failureCount and the handler's retry policy:
     * - Exponential: delay = initialDelay * (backoffMultiplier ^ failureCount)
     * - Fixed: delay = constant regardless of attempt count
     * - Linear: delay = initialDelay * (failureCount + 1)
     *
     * Example with exponential backoff (initialDelay=10s, multiplier=2):
     * ```
     * Attempt 1 fails: delay = 10s * 2^0 = 10s
     * Attempt 2 fails: delay = 10s * 2^1 = 20s
     * Attempt 3 fails: delay = 10s * 2^2 = 40s
     * Attempt 4 would fail (exhausted): marked FAILED
     * ```
     *
     * ## State Transitions
     *
     * ```
     * NEW -> (handler throws exception)
     *   ├─ FAILED: if retries exhausted OR policy says non-retryable
     *   └─ NEW: if retries remaining AND policy says retryable
     *       (record is updated with nextRetryTime and incremented failureCount)
     * ```
     *
     * @param record The record that failed processing
     * @param ex The exception thrown by the handler
     */
    private fun handleFailure(
        record: OutboxRecord<*>,
        ex: Exception,
    ) {
        log.debug("Failed processing record {} for key {}: {}", record.id, record.key, ex.message)

        record.incrementFailureCount()
        record.updateFailureReason(ex.message)

        val retryPolicy = retryPolicyRegistry.getByHandlerId(record.handlerId)

        val retriesExhausted = record.retriesExhausted(retryPolicy.maxRetries())
        if (retriesExhausted || !retryPolicy.shouldRetry(ex)) {
            record.markFailed()

            log.warn(
                "Record {} for record key {} marked as FAILED after {} failures{}",
                record.id,
                record.key,
                record.failureCount,
                if (retriesExhausted) " (retries exhausted)" else " (non-retryable exception)",
            )
        } else {
            val delay = retryPolicy.nextDelay(record.failureCount)
            record.scheduleNextRetry(delay, clock)

            log.debug(
                "Scheduled retry #{} for record {} in {}",
                record.failureCount,
                record.id,
                delay,
            )
        }

        recordRepository.save(record)
    }
}
