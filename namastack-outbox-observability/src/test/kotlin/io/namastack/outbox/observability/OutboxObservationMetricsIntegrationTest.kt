package io.namastack.outbox.observability

import io.micrometer.core.instrument.MeterRegistry
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OutboxObservationMetricsIntegrationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    MetricsAutoConfiguration::class.java,
                    SimpleMetricsExportAutoConfiguration::class.java,
                    ObservationAutoConfiguration::class.java,
                    OutboxObservabilityAutoConfiguration::class.java,
                ),
            ).withPropertyValues("management.metrics.use-global-registry=false")

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `outbox timers work with default settings and active long task timer opt in`(includeActiveTimer: Boolean) {
        val runner =
            if (includeActiveTimer) {
                contextRunner.withPropertyValues("management.metrics.observations.include-active-long-task-timer=true")
            } else {
                contextRunner
            }

        runner.run { context ->
            assertThat(context).hasNotFailed()
            val registry = context.getBean(MeterRegistry::class.java)
            val instrumentation = context.getBean(MicrometerOutboxInstrumentation::class.java)
            val name = OutboxMetricNames.RECORD_SCHEDULE

            instrumentation.schedule(
                OutboxScheduleInvocation(payload = "message", recordKey = "order-42", channel = "orders"),
            ) {
                val activeTimer = registry.find("$name.active").longTaskTimer()
                if (includeActiveTimer) {
                    assertThat(activeTimer).isNotNull()
                    assertThat(activeTimer!!.activeTasks()).isEqualTo(1)
                } else {
                    assertThat(activeTimer).isNull()
                }
            }

            assertThat(
                registry
                    .get(name)
                    .tag("outbox.channel", "orders")
                    .timer()
                    .count(),
            ).isEqualTo(1)
            if (includeActiveTimer) {
                assertThat(registry.get("$name.active").longTaskTimer().activeTasks()).isZero()
            }
        }
    }
}
