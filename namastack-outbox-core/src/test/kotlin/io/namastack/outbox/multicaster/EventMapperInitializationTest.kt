package io.namastack.outbox.multicaster

import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.config.OutboxCoreMulticasterAutoConfiguration
import io.namastack.outbox.mapping.EventMapper
import jakarta.annotation.PostConstruct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.transaction.support.TransactionSynchronizationManager

class EventMapperInitializationTest {
    private val outbox = mockk<Outbox>(relaxed = true)
    private val runner =
        ApplicationContextRunner()
            .withAllowCircularReferences(false)
            .withConfiguration(AutoConfigurations.of(OutboxCoreMulticasterAutoConfiguration::class.java))
            .withBean(OutboxProperties::class.java, {
                OutboxProperties(multicaster = OutboxProperties.Multicaster(publishAfterSave = false))
            })
            .withBean(Outbox::class.java, { outbox })
            .withBean(Recorder::class.java, { Recorder() })

    @AfterEach
    fun cleanup() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `mapper may publish an unrelated event from PostConstruct without circular references`() {
        runner.withUserConfiguration(PublishingMapperConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val recorder = context.getBean(Recorder::class.java)
            assertThat(recorder.events).containsExactly(StartupSignal("mapper"))
            context.publishEvent(Source("ready"))
            assertThat(recorder.events).containsExactly(StartupSignal("mapper"), Target("ready"), Source("ready"))
            assertThat(recorder.mappings).isEqualTo(1)
        }
    }

    @Test
    fun `mapper constructor dependency may publish during initialization`() {
        runner.withUserConfiguration(DependencyConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val recorder = context.getBean(Recorder::class.java)
            assertThat(recorder.events).containsExactly(StartupSignal("dependency"))
            context.publishEvent(Source("ready"))
            assertThat(recorder.events).containsExactly(StartupSignal("dependency"), Target("ready"), Source("ready"))
            assertThat(recorder.mappings).isEqualTo(1)
        }
    }

    @Test
    fun `lazy mapper may publish while the registry is being initialized`() {
        runner.withUserConfiguration(LazyMapperConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val recorder = context.getBean(Recorder::class.java)
            assertThat(recorder.events).containsExactly(StartupSignal("lazy-mapper"))
            context.publishEvent(Source("ready"))
            assertThat(recorder.events).containsExactly(StartupSignal("lazy-mapper"), Target("ready"), Source("ready"))
        }
    }

    @Test
    fun `early mappable source uses normal publication and is not replayed after initialization`() {
        runner.withUserConfiguration(EarlySourceConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val recorder = context.getBean(Recorder::class.java)
            assertThat(recorder.events).containsExactly(Source("early"))
            assertThat(recorder.mappings).isZero()
            context.publishEvent(Source("ready"))
            assertThat(recorder.events).containsExactly(Source("early"), Target("ready"), Source("ready"))
            assertThat(recorder.mappings).isEqualTo(1)
        }
    }

    @Test
    fun `early annotated events retain direct outbox scheduling without initializing mappers`() {
        runner.withUserConfiguration(EarlyOutboxConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            verify(exactly = 1) { outbox.schedule(StartupOutboxMessage("startup-1"), "startup-1", emptyMap()) }
            val recorder = context.getBean(Recorder::class.java)
            assertThat(recorder.events).isEmpty()
            context.publishEvent(Source("ready"))
            assertThat(recorder.events).containsExactly(Target("ready"), Source("ready"))
        }
    }

    data class Source(
        val id: String,
    )

    data class Target(
        val id: String,
    )

    data class StartupSignal(
        val origin: String,
    )

    @OutboxEvent(key = "#this.id")
    data class StartupOutboxMessage(
        val id: String,
    )

    class Recorder : ApplicationListener<PayloadApplicationEvent<*>> {
        val events = mutableListOf<Any>()
        var mappings = 0

        override fun onApplicationEvent(event: PayloadApplicationEvent<*>) {
            events += event.payload
        }
    }

    class PublishingMapper(
        private val publisher: ApplicationEventPublisher,
        private val recorder: Recorder,
        private val startupEvent: Any,
        private val transactionalStartup: Boolean = false,
    ) : EventMapper<Source, Target> {
        @PostConstruct
        fun initialize() {
            if (transactionalStartup) TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                publisher.publishEvent(startupEvent)
            } finally {
                if (transactionalStartup) TransactionSynchronizationManager.clear()
            }
        }

        override fun map(event: Source): Target {
            recorder.mappings++
            return Target(event.id)
        }
    }

    class PublishingDependency(
        private val publisher: ApplicationEventPublisher,
        val recorder: Recorder,
    ) {
        var initialized = false

        @PostConstruct
        fun initialize() {
            publisher.publishEvent(StartupSignal("dependency"))
            initialized = true
        }
    }

    class DependentMapper(
        private val dependency: PublishingDependency,
    ) : EventMapper<Source, Target> {
        override fun map(event: Source): Target {
            check(dependency.initialized)
            dependency.recorder.mappings++
            return Target(event.id)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class PublishingMapperConfiguration {
        @Bean
        fun mapper(
            publisher: ApplicationEventPublisher,
            recorder: Recorder,
        ) = PublishingMapper(publisher, recorder, StartupSignal("mapper"))
    }

    @Configuration(proxyBeanMethods = false)
    class DependencyConfiguration {
        @Bean
        fun dependency(
            publisher: ApplicationEventPublisher,
            recorder: Recorder,
        ) = PublishingDependency(publisher, recorder)

        @Bean
        fun mapper(dependency: PublishingDependency) = DependentMapper(dependency)
    }

    @Configuration(proxyBeanMethods = false)
    class LazyMapperConfiguration {
        @Bean
        @Lazy
        fun mapper(
            publisher: ApplicationEventPublisher,
            recorder: Recorder,
        ) = PublishingMapper(publisher, recorder, StartupSignal("lazy-mapper"))
    }

    @Configuration(proxyBeanMethods = false)
    class EarlySourceConfiguration {
        @Bean
        fun mapper(
            publisher: ApplicationEventPublisher,
            recorder: Recorder,
        ) = PublishingMapper(publisher, recorder, Source("early"))
    }

    @Configuration(proxyBeanMethods = false)
    class EarlyOutboxConfiguration {
        @Bean
        fun mapper(
            publisher: ApplicationEventPublisher,
            recorder: Recorder,
        ) = PublishingMapper(publisher, recorder, StartupOutboxMessage("startup-1"), transactionalStartup = true)
    }
}
