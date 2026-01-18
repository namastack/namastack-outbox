package io.namastack.outbox.retry

import java.time.Duration
import kotlin.math.pow

/**
 * Factory for common [BackOffStrategy] implementations.
 *
 * @author Roland Beisel
 * @since 1.0.0-RC2
 */
object BackOffStrategies {
    /**
     * Creates a fixed delay backoff strategy.
     */
    @JvmStatic
    fun fixed(delay: Duration): BackOffStrategy = FixedBackOffStrategy(delay)

    /**
     * Creates an exponential backoff strategy: `initialDelay * multiplier^(failureCount-1)`
     */
    @JvmStatic
    @JvmOverloads
    fun exponential(
        initialDelay: Duration,
        multiplier: Double = 2.0,
        maxDelay: Duration? = null,
    ): BackOffStrategy = ExponentialBackOffStrategy(initialDelay, multiplier, maxDelay)

    /**
     * Creates a linear backoff strategy: `initialDelay + (increment * (failureCount-1))`
     */
    @JvmStatic
    @JvmOverloads
    fun linear(
        initialDelay: Duration,
        increment: Duration,
        maxDelay: Duration? = null,
    ): BackOffStrategy = LinearBackOffStrategy(initialDelay, increment, maxDelay)
}

/**
 * Fixed delay backoff strategy.
 *
 * Returns the same delay regardless of the failure count.
 *
 * @param delay The fixed delay between retries
 */
internal class FixedBackOffStrategy(
    private val delay: Duration,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration = delay
}

/**
 * Exponential backoff strategy.
 *
 * Calculates delay as: `initialDelay * multiplier^(failureCount-1)`, capped at [maxDelay].
 *
 * @param initialDelay Initial delay for the first retry
 * @param multiplier Multiplier applied for each subsequent retry
 * @param maxDelay Maximum delay cap (null = no cap)
 */
internal class ExponentialBackOffStrategy(
    private val initialDelay: Duration,
    private val multiplier: Double,
    private val maxDelay: Duration?,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration {
        val factor = multiplier.pow((failureCount - 1).toDouble())
        val delayMillis = (initialDelay.toMillis() * factor).toLong()
        val delay = Duration.ofMillis(delayMillis)
        return maxDelay?.let { minOf(delay, it) } ?: delay
    }

    private fun minOf(
        a: Duration,
        b: Duration,
    ): Duration = if (a <= b) a else b
}

/**
 * Linear backoff strategy.
 *
 * Calculates delay as: `initialDelay + (increment * (failureCount-1))`, capped at [maxDelay].
 *
 * @param initialDelay Initial delay for the first retry
 * @param increment Amount to add for each subsequent retry
 * @param maxDelay Maximum delay cap (null = no cap)
 */
internal class LinearBackOffStrategy(
    private val initialDelay: Duration,
    private val increment: Duration,
    private val maxDelay: Duration?,
) : BackOffStrategy {
    override fun nextDelay(failureCount: Int): Duration {
        val incrementMillis = increment.toMillis() * (failureCount - 1)
        val delayMillis = initialDelay.toMillis() + incrementMillis
        val delay = Duration.ofMillis(delayMillis)
        return maxDelay?.let { minOf(delay, it) } ?: delay
    }

    private fun minOf(
        a: Duration,
        b: Duration,
    ): Duration = if (a <= b) a else b
}
