package io.namastack.outbox.observability.aop

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxScheduleMatcherPointcut")
class OutboxScheduleMatcherPointcutTest {
    private val pointcut = OutboxScheduleMatcherPointcut()

    @Nested
    @DisplayName("Method Matching")
    inner class MethodMatching {
        @Test
        fun `matches schedule methods`() {
            val method = Outbox::class.java.getMethod("schedule", Any::class.java)

            assertThat(pointcut.matches(method, OutboxService::class.java)).isTrue()
        }

        @Test
        fun `does not match unrelated method name`() {
            val method = Any::class.java.getMethod("toString")

            assertThat(pointcut.matches(method, OutboxService::class.java)).isFalse()
        }
    }

    @Nested
    @DisplayName("Class Filtering")
    inner class ClassFiltering {
        @Test
        fun `matches outbox implementations`() {
            assertThat(pointcut.classFilter.matches(OutboxService::class.java)).isTrue()
        }

        @Test
        fun `does not match non-outbox classes`() {
            assertThat(pointcut.classFilter.matches(String::class.java)).isFalse()
        }
    }
}
