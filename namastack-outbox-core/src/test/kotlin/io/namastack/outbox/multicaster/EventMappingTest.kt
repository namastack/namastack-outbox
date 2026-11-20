package io.namastack.outbox.multicaster

import example.eventmapping.ComponentMapper
import example.eventmapping.ConfiguredMapper
import example.eventmapping.DomainEvent
import example.eventmapping.Formatter
import example.eventmapping.IntegrationEvent
import example.eventmapping.LocalSource
import example.eventmapping.LocalTarget
import example.mapperinheritance.conflict.ChildMapper
import example.mapperinheritance.conflict.ParentMapper
import example.mapperinheritance.valid.InheritedMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.config.OutboxCoreMulticasterAutoConfiguration
import io.namastack.outbox.mapping.EventMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.transaction.support.TransactionSynchronizationManager

class EventMappingTest {
    @Test
    fun `native application event source is mapped and remains available to local listeners`() {
        runner
            .withBean("nativeSourceMapper", EventMapper::class.java, {
                object : EventMapper<NativeSource, LocalTarget> {
                    override fun map(event: NativeSource): LocalTarget = LocalTarget(event.id)
                }
            })
            .run { context ->
                val delivered = mutableListOf<Any>()
                context.addApplicationListener { event ->
                    if (event is NativeSource) delivered += event
                    if (event is PayloadApplicationEvent<*>) delivered += event.payload
                }
                val source = NativeSource("native-source-1")
                context.publishEvent(source)
                assertThat(delivered).containsExactly(LocalTarget("native-source-1"), source)
                verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
            }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `native application event target is persisted and honors local publication settings`(
        publishAfterSave: Boolean,
    ) {
        runner
            .withAllowBeanDefinitionOverriding(true)
            .withBean(OutboxProperties::class.java, {
                OutboxProperties(multicaster = OutboxProperties.Multicaster(publishAfterSave = publishAfterSave))
            })
            .withBean(NativeTargetListener::class.java, { NativeTargetListener() })
            .withBean("nativeMapper", EventMapper::class.java, {
                object : EventMapper<LocalSource, NativeTarget> {
                    override fun map(event: LocalSource): NativeTarget = NativeTarget(event.id)
                }
            })
            .run { context ->
                TransactionSynchronizationManager.setActualTransactionActive(true)
                context.publishEvent(LocalSource("native-1"))
                verify(
                    exactly = 1,
                ) { outbox.schedule(match<NativeTarget> { it.id == "native-1" }, "native-1", emptyMap()) }
                assertThat(context.getBean(NativeTargetListener::class.java).events)
                    .hasSize(if (publishAfterSave) 1 else 0)
            }
    }

    private val outbox = mockk<Outbox>(relaxed = true)
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxCoreMulticasterAutoConfiguration::class.java))
            .withBean(OutboxProperties::class.java, {
                OutboxProperties(multicaster = OutboxProperties.Multicaster(publishAfterSave = false))
            })
            .withBean(Outbox::class.java, { outbox })
    private val mapperRunner =
        runner
            .withUserConfiguration(MapperApplication::class.java)
            .withBean(Formatter::class.java, { Formatter() })

    @AfterEach
    fun cleanup() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `uses component and factory mapper beans with constructor injection`() {
        mapperRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(ConfiguredMapper::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(ComponentMapper::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(EventMapper::class.java)).hasSize(2)
            val delivered = mutableListOf<Any>()
            context.addApplicationListener { if (it is PayloadApplicationEvent<*>) delivered += it.payload }
            TransactionSynchronizationManager.setActualTransactionActive(true)

            context.publishEvent(DomainEvent("order-1"))

            verify(exactly = 1) {
                outbox.schedule(IntegrationEvent("ORDER-1"), "mapped:ORDER-1", mapOf("origin" to "mapper"))
            }
            assertThat(delivered).containsExactly(DomainEvent("order-1"))
        }
    }

    @Test
    fun `unregistered mapper implementations are ignored`() {
        runner.withUserConfiguration(UnregisteredMapperApplication::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(EventMapper::class.java)).isEmpty()
            val delivered = mutableListOf<Any>()
            context.addApplicationListener { if (it is PayloadApplicationEvent<*>) delivered += it.payload }
            context.publishEvent(DomainEvent("unmapped-1"))
            assertThat(delivered).containsExactly(DomainEvent("unmapped-1"))
            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
        }
    }

    @Test
    fun `unannotated targets need no transaction and reach local listeners together with the source`() {
        mapperRunner.run { context ->
            val events = mutableListOf<PayloadApplicationEvent<*>>()
            context.addApplicationListener { if (it is PayloadApplicationEvent<*>) events += it }

            context.publishEvent(LocalSource("local-1"))

            assertThat(events.map { it.payload }).containsExactly(LocalTarget("local-1"), LocalSource("local-1"))
            assertThat(events.first().source).isSameAs(events.last().source)
            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
        }
    }

    @Test
    fun `typed Spring event listeners receive the mapped and original payload types`() {
        mapperRunner.withBean(TypedListeners::class.java, { TypedListeners() }).run { context ->
            context.publishEvent(LocalSource("typed-1"))
            val listeners = context.getBean(TypedListeners::class.java)
            assertThat(listeners.sources).containsExactly(LocalSource("typed-1"))
            assertThat(listeners.targets).containsExactly(LocalTarget("typed-1"))
        }
    }

    @Test
    fun `annotated target still requires an active transaction`() {
        mapperRunner.run { context ->
            assertThatThrownBy { context.publishEvent(DomainEvent("order-1")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("OutboxEvent requires an active transaction")
            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
        }
    }

    @Test
    fun `publishAfterSave applies to the mapped target but not the original`() {
        mapperRunner
            .withAllowBeanDefinitionOverriding(true)
            .withBean(OutboxProperties::class.java, {
                OutboxProperties(multicaster = OutboxProperties.Multicaster(publishAfterSave = true))
            })
            .run { context ->
                val events = mutableListOf<Any>()
                context.addApplicationListener { if (it is PayloadApplicationEvent<*>) events += it.payload }
                TransactionSynchronizationManager.setActualTransactionActive(true)

                context.publishEvent(DomainEvent("order-1"))

                assertThat(events).containsExactly(IntegrationEvent("ORDER-1"), DomainEvent("order-1"))
            }
    }

    @Test
    fun `mapped targets do not start another mapping round`() {
        mapperRunner
            .withBean("secondMapper", EventMapper::class.java, {
                object : EventMapper<LocalTarget, String> {
                    override fun map(event: LocalTarget): String = error("Must not chain")
                }
            })
            .run { context ->
                val events = mutableListOf<Any>()
                context.addApplicationListener { if (it is PayloadApplicationEvent<*>) events += it.payload }
                context.publishEvent(LocalSource("local-1"))
                assertThat(events).containsExactly(LocalTarget("local-1"), LocalSource("local-1"))
            }
    }

    @Test
    fun `mapping and persistence failures propagate before delivering the source`() {
        mapperRunner.run { context ->
            val events = mutableListOf<Any>()
            context.addApplicationListener { if (it is PayloadApplicationEvent<*>) events += it.payload }
            TransactionSynchronizationManager.setActualTransactionActive(true)
            every { outbox.schedule(any(), any(), any()) } throws IllegalStateException("Scheduling failed")
            assertThatThrownBy { context.publishEvent(DomainEvent("order-1")) }.hasMessage("Scheduling failed")
            assertThat(events).isEmpty()
        }
        runner
            .withBean("failingMapper", EventMapper::class.java, {
                object : EventMapper<LocalSource, LocalTarget> {
                    override fun map(event: LocalSource): LocalTarget = error("Mapping failed")
                }
            })
            .run { context ->
                assertThatThrownBy { context.publishEvent(LocalSource("local-1")) }.hasMessage("Mapping failed")
            }
    }

    @Test
    fun `duplicate source registrations fail at startup`() {
        mapperRunner
            .withBean("duplicateMapper", EventMapper::class.java, {
                object : EventMapper<DomainEvent, String> {
                    override fun map(event: DomainEvent): String = event.id
                }
            })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("Multiple EventMappers registered for")
            }
    }

    @Test
    fun `annotated mapper source fails at startup`() {
        runner
            .withBean("invalidMapper", EventMapper::class.java, {
                object : EventMapper<IntegrationEvent, String> {
                    override fun map(event: IntegrationEvent): String = event.id
                }
            })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("source must not declare @OutboxEvent")
            }
    }

    @Test
    fun `mapping falls back to an assignable source type`() {
        val mapper =
            object : EventMapper<Any, String> {
                override fun map(event: Any): String = "mapped"
            }
        assertThat(EventMapperRegistry(listOf(mapper)).mapIfSupported(LocalSource("local-1"))).isEqualTo("mapped")
    }

    @Test
    fun `uses mapper bean with inherited generic contract source type and mapping method`() {
        runner.withBean(InheritedMapper::class.java, { InheritedMapper() }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(EventMapper::class.java)).hasSize(1)
            val payloads = mutableListOf<Any>()
            context.addApplicationListener { if (it is PayloadApplicationEvent<*>) payloads += it.payload }
            val source = example.mapperinheritance.valid.Source("inherited-1")
            context.publishEvent(source)
            assertThat(payloads).containsExactly(source.toString(), source)
        }
    }

    @Test
    fun `concrete mapper parent and child are ambiguous and fail at startup`() {
        runner
            .withBean(ParentMapper::class.java, { ParentMapper() })
            .withBean(ChildMapper::class.java, { ChildMapper() })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("Multiple EventMappers registered for")
            }
    }

    @Test
    fun `mapper for abstract event base handles concrete subclasses`() {
        val mapper =
            object : EventMapper<BaseEvent, String> {
                override fun map(event: BaseEvent): String = "mapped"
            }
        assertThat(EventMapperRegistry(listOf(mapper)).mapIfSupported(ChildEvent())).isEqualTo("mapped")
    }

    abstract class BaseEvent

    class ChildEvent : BaseEvent()

    @Test
    fun `disabled multicaster leaves mapper beans unused`() {
        mapperRunner.withPropertyValues("namastack.outbox.multicaster.enabled=false").run { context ->
            assertThat(context.getBeansOfType(EventMapper::class.java)).hasSize(2)
            assertThat(context.getBeansOfType(OutboxEventMulticaster::class.java)).isEmpty()
            val delivered = mutableListOf<Any>()
            context.addApplicationListener { if (it is PayloadApplicationEvent<*>) delivered += it.payload }
            context.publishEvent(DomainEvent("disabled-1"))
            assertThat(delivered).containsExactly(DomainEvent("disabled-1"))
            verify(exactly = 0) { outbox.schedule(any(), any(), any()) }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = ["example.eventmapping"])
    class UnregisteredMapperApplication

    @Configuration(proxyBeanMethods = false)
    @Import(ComponentMapper::class)
    class MapperApplication {
        @Bean
        fun orderMapper(formatter: Formatter): EventMapper<DomainEvent, IntegrationEvent> = ConfiguredMapper(formatter)
    }

    class TypedListeners {
        val sources = mutableListOf<LocalSource>()
        val targets = mutableListOf<LocalTarget>()

        @EventListener
        fun source(event: LocalSource) {
            sources += event
        }

        @EventListener
        fun target(event: LocalTarget) {
            targets += event
        }
    }

    @OutboxEvent(key = "#this.id")
    class NativeTarget(
        val id: String,
    ) : ApplicationEvent("mapper-source")

    class NativeSource(
        val id: String,
    ) : ApplicationEvent("native-source")

    class NativeTargetListener : ApplicationListener<NativeTarget> {
        val events = mutableListOf<NativeTarget>()

        override fun onApplicationEvent(event: NativeTarget) {
            events += event
        }
    }
}
