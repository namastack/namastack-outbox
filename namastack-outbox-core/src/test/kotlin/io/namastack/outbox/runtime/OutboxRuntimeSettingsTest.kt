package io.namastack.outbox.runtime

import io.namastack.outbox.runtime.OutboxRuntimeSettings.CompletionBatching
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * Tests completion batch sizes and batch deadlines.
 *
 * @author Roland Beisel
 */
class OutboxRuntimeSettingsTest {
    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `rejects nonpositive completion batch sizes`(size: Int) {
        assertThatThrownBy { CompletionBatching(maxBatchSize = size) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("maxBatchSize must be positive")
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `rejects nonpositive batch waits`(nanos: Long) {
        assertThatThrownBy { CompletionBatching(maxWait = Duration.ofNanos(nanos)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("maxWait must be positive")
    }

    @Test
    fun `rejects a batch wait that overflows nanoseconds`() {
        assertThatThrownBy { CompletionBatching(maxWait = Duration.ofSeconds(Long.MAX_VALUE)) }
            .isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `accepts the smallest positive batching settings`() {
        val settings = CompletionBatching(maxBatchSize = 1, maxWait = Duration.ofNanos(1))

        assertThat(settings.maxBatchSize).isOne()
        assertThat(settings.maxWait).isEqualTo(Duration.ofNanos(1))
    }
}
