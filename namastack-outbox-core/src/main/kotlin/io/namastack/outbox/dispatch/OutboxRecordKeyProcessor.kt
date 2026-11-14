package io.namastack.outbox.dispatch

import io.namastack.outbox.OutboxHandlerNotFoundException
import io.namastack.outbox.OutboxPayloadTypeNotFoundException
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordPage
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Processes the loaded records of one record key and tracks the compatibility cooldown.
 *
 * Records of a key are processed sequentially in page order. With
 * [OutboxRuntimeSettings.Processing.stopOnFirstFailure] enabled, processing of the key stops at the
 * first record that is not completed. A record whose payload type or handler is unavailable on this
 * instance activates a compatibility cooldown, during which the scheduler skips polling.
 *
 * This type is part of the processing runtime and shared by all [KeyDispatcher] implementations.
 * It is not intended to be used or implemented by applications.
 *
 * @param recordProcessorChainInvoker Invoker for the instrumented record processor chain
 * @param settings Effective processing settings
 * @param clock Clock for retry readiness and cooldown calculations
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
class OutboxRecordKeyProcessor(
    private val recordProcessorChainInvoker: OutboxRecordProcessorChainInvoker,
    private val settings: OutboxRuntimeSettings.Processing,
    private val clock: Clock,
) {
    private companion object {
        private val COMPATIBILITY_COOLDOWN: Duration = Duration.ofSeconds(30)
    }

    // Processing log output uses the scheduler's logger category.
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    private val compatibilityCooldownUntil = AtomicReference<Instant?>()

    /**
     * Processes the records of one key in page order.
     *
     * Exceptions are logged and never propagated.
     *
     * @param recordKey Storage key of the records
     * @param page Incomplete records of the key in processing order
     * @return `true` when the key was processed without an unexpected exception, `false` when
     *         processing ended with an exception (including compatibility failures)
     */
    fun processRecordKey(
        recordKey: String,
        page: OutboxRecordPage,
    ): Boolean {
        try {
            if (page.isEmpty()) return true

            log.trace("Processing {} records for key {}", page.size, recordKey)

            for (index in 0 until page.size) {
                if (!processRecord(page[index])) break
            }
            return true
        } catch (ex: OutboxPayloadTypeNotFoundException) {
            handleUnavailablePayloadType(ex)
        } catch (ex: OutboxHandlerNotFoundException) {
            handleUnavailableHandler(ex)
        } catch (ex: Exception) {
            log.error("Error processing key {}", recordKey, ex)
        }
        return false
    }

    /**
     * Reports whether a compatibility cooldown is active and clears an expired one.
     *
     * @return `true` while polling must be skipped because of a compatibility failure
     */
    fun isCompatibilityCooldownActive(): Boolean {
        val cooldownUntil = compatibilityCooldownUntil.get() ?: return false
        if (clock.instant().isBefore(cooldownUntil)) {
            log.trace(
                "Skipping outbox polling until {} because this scheduler instance encountered a compatibility failure",
                cooldownUntil,
            )
            return true
        }

        compatibilityCooldownUntil.compareAndSet(cooldownUntil, null)
        return false
    }

    private fun processRecord(record: OutboxRecord<*>): Boolean {
        if (!record.canBeRetried(clock)) {
            log.trace("Skipping record {} - not ready for retry", record.id)
            return continueOnFailure()
        }

        val outcome = recordProcessorChainInvoker.process(record)

        return outcome == OutboxRecordProcessingOutcome.COMPLETED || continueOnFailure()
    }

    private fun continueOnFailure(): Boolean = !settings.stopOnFirstFailure

    private fun handleUnavailablePayloadType(ex: OutboxPayloadTypeNotFoundException) {
        val cooldownUntil = activateCompatibilityCooldown()
        log.warn(
            "Payload type {} is unavailable on this scheduler instance; pausing polling until {} and leaving " +
                "the record pending without consuming a delivery retry " +
                "(recordId={}, businessKey={}, processingGroup={}, storageKey={}, handlerId={})",
            ex.payloadType,
            cooldownUntil,
            ex.recordId,
            ex.businessKey,
            ex.processingGroup,
            ex.recordKey,
            ex.handlerId,
            ex,
        )
    }

    private fun handleUnavailableHandler(ex: OutboxHandlerNotFoundException) {
        val cooldownUntil = activateCompatibilityCooldown()
        log.warn(
            "Handler {} is unavailable on this scheduler instance; pausing polling until {} and leaving the " +
                "record pending without consuming a delivery retry " +
                "(recordId={}, businessKey={}, processingGroup={}, storageKey={})",
            ex.handlerId,
            cooldownUntil,
            ex.recordId,
            ex.businessKey,
            ex.processingGroup,
            ex.recordKey,
            ex,
        )
    }

    private fun activateCompatibilityCooldown(): Instant {
        val requestedCooldownUntil = clock.instant().plus(COMPATIBILITY_COOLDOWN)

        val cooldownUntil =
            compatibilityCooldownUntil.updateAndGet { currentCooldownUntil ->
                if (currentCooldownUntil == null || requestedCooldownUntil.isAfter(currentCooldownUntil)) {
                    requestedCooldownUntil
                } else {
                    currentCooldownUntil
                }
            }

        return checkNotNull(cooldownUntil)
    }
}
