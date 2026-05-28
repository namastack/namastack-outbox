package io.namastack.outbox.observability.aop

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxInvokerMatcherPointcut")
class OutboxInvokerMatcherPointcutTest {
    @Nested
    @DisplayName("Method Matching")
    inner class MethodMatching {
        @Test
        fun `matches dispatch method with outbox record argument`() {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)
            val method = OutboxHandlerInvoker::class.java.getMethod("dispatch", OutboxRecord::class.java)

            assertThat(pointcut.matches(method, OutboxHandlerInvoker::class.java)).isTrue()
        }

        @Test
        fun `matches fallback dispatch method with outbox record argument`() {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxFallbackHandlerInvoker::class.java)
            val method = OutboxFallbackHandlerInvoker::class.java.getMethod("dispatch", OutboxRecord::class.java)

            assertThat(pointcut.matches(method, OutboxFallbackHandlerInvoker::class.java)).isTrue()
        }

        @Test
        fun `does not match unrelated method name`() {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)
            val method = Any::class.java.getMethod("toString")

            assertThat(pointcut.matches(method, OutboxHandlerInvoker::class.java)).isFalse()
        }

        @Test
        fun `does not match dispatch method with non-record argument`() {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)
            val method = DispatchFixture::class.java.getMethod("dispatch", String::class.java)

            assertThat(pointcut.matches(method, DispatchFixture::class.java)).isFalse()
        }
    }

    @Nested
    @DisplayName("Class Filtering")
    inner class ClassFiltering {
        @Test
        fun `matches only configured invoker class`() {
            val pointcut = OutboxInvokerMatcherPointcut(OutboxHandlerInvoker::class.java)

            assertThat(pointcut.classFilter.matches(OutboxHandlerInvoker::class.java)).isTrue()
            assertThat(pointcut.classFilter.matches(OutboxFallbackHandlerInvoker::class.java)).isFalse()
            assertThat(pointcut.classFilter.matches(String::class.java)).isFalse()
        }
    }

    private class DispatchFixture {
        @Suppress("unused")
        fun dispatch(value: String) = value
    }
}
