package io.namastack.outbox.multicaster

import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.mapping.EventMapper
import org.aopalliance.intercept.MethodInterceptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.framework.ProxyFactory

class EventMapperRegistryTest {
    @Test
    fun `rejects erased generic sources instead of registering an accidental catch all`() {
        assertThatThrownBy { EventMapperRegistry(listOf(ErasedMapper<ProxySource>())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot resolve first type argument")
    }

    @Test
    fun `resolves source type inherited through a specialized mapper interface`() {
        val mapper =
            object : SpecializedMapper {
                override fun map(event: ProxySource): String = event.id
            }
        assertThat(EventMapperRegistry(listOf(mapper)).mapIfSupported(ProxySource("interface-1")))
            .isEqualTo("interface-1")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `resolves mapper generics through AOP proxies and invokes the advised bean`(classProxy: Boolean) {
        val calls = mutableListOf<String>()
        val factory = ProxyFactory(ProxiedMapper())
        factory.isProxyTargetClass = classProxy
        factory.addAdvice(
            MethodInterceptor { invocation ->
                calls += invocation.method.name
                invocation.proceed()
            },
        )
        val proxy = factory.proxy as EventMapper<*, *>

        assertThat(EventMapperRegistry(listOf(proxy)).mapIfSupported(ProxySource("proxied-1"))).isEqualTo("proxied-1")
        assertThat(calls).contains("map")
    }

    @Test
    fun `exact match wins even when fallback interfaces are ambiguous`() {
        val registry =
            EventMapperRegistry(
                listOf(
                    mapper<Left>("left"),
                    mapper<Right>("right"),
                    mapper<Both>("exact"),
                ),
            )
        assertThat(registry.mapIfSupported(Both())).isEqualTo("exact")
    }

    @Test
    fun `most specific class wins independently of registration order`() {
        val mappers = listOf(mapper<Base>("base"), mapper<Middle>("middle"))
        for (order in listOf(mappers, mappers.reversed())) {
            assertThat(EventMapperRegistry(order).mapIfSupported(Child())).isEqualTo("middle")
        }
    }

    @Test
    fun `most specific interface wins independently of registration order`() {
        val mappers = listOf(mapper<Left>("left"), mapper<SpecificLeft>("specific"))
        for (order in listOf(mappers, mappers.reversed())) {
            assertThat(EventMapperRegistry(order).mapIfSupported(Tagged())).isEqualTo("specific")
        }
    }

    @Test
    fun `class implementing an interface is more specific than that interface`() {
        val registry =
            EventMapperRegistry(
                listOf(
                    mapper<Left>("interface"),
                    mapper<TaggedBase>("class"),
                ),
            )
        assertThat(registry.mapIfSupported(TaggedChild())).isEqualTo("class")
    }

    @Test
    fun `unrelated matching interfaces fail before any mapper runs`() {
        val mappers = listOf(failingMapper<Left>(), failingMapper<Right>())
        for (order in listOf(mappers, mappers.reversed())) {
            assertThatThrownBy { EventMapperRegistry(order).mapIfSupported(Both()) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Ambiguous EventMappers")
                .hasMessageContaining(Both::class.java.name)
                .hasMessageContaining(Left::class.java.name)
                .hasMessageContaining(Right::class.java.name)
        }
    }

    @Test
    fun `unrelated matching superclass and interface are ambiguous`() {
        val registry = EventMapperRegistry(listOf(failingMapper<Base>(), failingMapper<Right>()))
        assertThatThrownBy { registry.mapIfSupported(ClassAndInterface()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Ambiguous EventMappers")
    }

    @Test
    fun `annotated runtime subclass is rejected before mapping through an unannotated base`() {
        val registry = EventMapperRegistry(listOf(failingMapper<Base>()))
        assertThatThrownBy { registry.mapIfSupported(AnnotatedChild()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("source must not declare @OutboxEvent")
            .hasMessageContaining(AnnotatedChild::class.java.name)
    }

    @Test
    fun `unrelated payload stays unmapped`() {
        val registry = EventMapperRegistry(listOf(failingMapper<Base>()))
        assertThat(registry.mapIfSupported("unrelated")).isNull()
    }

    private inline fun <reified S : Any> mapper(value: String): EventMapper<S, String> =
        object : EventMapper<S, String> {
            override fun map(event: S): String = value
        }

    private inline fun <reified S : Any> failingMapper(): EventMapper<S, String> =
        object : EventMapper<S, String> {
            override fun map(event: S): String = error("Mapper must not run")
        }

    abstract class Base

    data class ProxySource(
        val id: String,
    )

    interface SpecializedMapper : EventMapper<ProxySource, String>

    class ErasedMapper<S : Any> : EventMapper<S, String> {
        override fun map(event: S): String = "erased"
    }

    open class ProxiedMapper : EventMapper<ProxySource, String> {
        override fun map(event: ProxySource): String = event.id
    }

    open class Middle : Base()

    class Child : Middle()

    interface Left

    interface SpecificLeft : Left

    interface Right

    class Both :
        Left,
        Right

    class Tagged : SpecificLeft

    open class TaggedBase : Left

    class TaggedChild : TaggedBase()

    class ClassAndInterface :
        Base(),
        Right

    @OutboxEvent
    class AnnotatedChild : Base()
}
