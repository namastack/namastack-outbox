package io.namastack.outbox

import io.namastack.outbox.OutboxRuntimeMode.CHANNELS
import io.namastack.outbox.OutboxRuntimeMode.SINGLE
import io.namastack.outbox.config.OutboxRuntimeModeAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OutboxRuntimeModeAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxRuntimeModeAutoConfiguration::class.java))

    @Test
    fun `missing mode selects single`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean<OutboxProperties>().mode).isEqualTo(SINGLE)
            assertThat(context.getBeansOfType(OutboxRuntimeModeProvider::class.java).values)
                .singleElement()
                .extracting(OutboxRuntimeModeProvider::getMode)
                .isEqualTo(SINGLE)
        }
    }

    @Test
    fun `explicit single selects single`() {
        contextRunner
            .withPropertyValues("namastack.outbox.mode=single")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean<OutboxProperties>().mode).isEqualTo(SINGLE)
            }
    }

    @Test
    fun `channels without provider fails with actionable message`() {
        contextRunner
            .withPropertyValues("namastack.outbox.mode=channels")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context.startupFailure))
                    .contains("Outbox runtime mode 'channels' is not supported")
                    .contains("Add a module that supports this mode")
                    .contains("namastack.outbox.mode=single")
            }
    }

    @Test
    fun `channels starts when provider is available`() {
        contextRunner
            .withBean("channelsRuntimeModeProvider", OutboxRuntimeModeProvider::class.java, {
                OutboxRuntimeModeProvider { CHANNELS }
            })
            .withPropertyValues("namastack.outbox.mode=channels")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean<OutboxProperties>().mode).isEqualTo(CHANNELS)
            }
    }

    @Test
    fun `unknown mode fails binding`() {
        contextRunner
            .withPropertyValues("namastack.outbox.mode=unknown")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context.startupFailure))
                    .contains("namastack.outbox")
                    .contains("unknown")
            }
    }

    @Test
    fun `duplicate provider for selected mode fails deterministically`() {
        contextRunner
            .withBean("secondSingleRuntimeModeProvider", OutboxRuntimeModeProvider::class.java, {
                OutboxRuntimeModeProvider { SINGLE }
            })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(failureMessages(context.startupFailure))
                    .contains("Outbox runtime mode 'single' has 2 providers")
                    .contains("exactly one OutboxRuntimeModeProvider must support the selected mode")
            }
    }

    private fun failureMessages(failure: Throwable?): String =
        generateSequence(failure) { throwable -> throwable.cause }
            .mapNotNull(Throwable::message)
            .joinToString("\n")
}
