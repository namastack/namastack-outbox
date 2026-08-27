package io.namastack.outbox.handler

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory

class ReflectionUtilsTest {
    @Test
    fun `getTargetClass returns bean class when not a proxy`() {
        val bean = TestBean()

        val result = ReflectionUtils.getTargetClass(bean)

        assertThat(result).isEqualTo(TestBean::class.java)
    }

    @Test
    fun `getTargetClass returns target class when bean is AOP proxy`() {
        val target = TestBean()
        val proxyFactory = ProxyFactory(target)
        proxyFactory.isProxyTargetClass = true
        val proxy = proxyFactory.proxy

        val result = ReflectionUtils.getTargetClass(proxy)

        assertThat(result).isEqualTo(TestBean::class.java)
    }

    @Test
    fun `getTargetClass returns target class for interface-based proxy`() {
        val target = TestHandlerImpl()
        val proxyFactory = ProxyFactory(target)
        proxyFactory.addInterface(TestHandlerInterface::class.java)
        val proxy = proxyFactory.proxy

        val result = ReflectionUtils.getTargetClass(proxy)

        assertThat(result).isEqualTo(TestHandlerImpl::class.java)
    }

    @Test
    fun `findInterfaceMethod selects method by resolved signature`() {
        val bean = OverloadedTypedHandler()

        val result =
            ReflectionUtils.findInterfaceMethod(
                bean = bean,
                handlerInterface = OutboxTypedHandler::class.java,
                methodName = "handle",
                contextType = OutboxRecordMetadata::class.java,
            )

        assertThat(result.parameterTypes).containsExactly(
            HandlerPayload::class.java,
            OutboxRecordMetadata::class.java,
        )
    }

    @Test
    fun `findInterfaceMethod finds exact method on proxy`() {
        val target = OverloadedTypedHandler()
        val proxyFactory = ProxyFactory(target)
        proxyFactory.addInterface(OutboxTypedHandler::class.java)
        val proxy = proxyFactory.proxy

        val result =
            ReflectionUtils.findInterfaceMethod(
                bean = proxy,
                handlerInterface = OutboxTypedHandler::class.java,
                methodName = "handle",
                contextType = OutboxRecordMetadata::class.java,
            )

        assertThat(result.parameterTypes.first()).isEqualTo(HandlerPayload::class.java)
    }

    @Test
    fun `findInterfaceMethod rejects bean that does not implement interface`() {
        val bean = TestBean()

        assertThatThrownBy {
            ReflectionUtils.findInterfaceMethod(
                bean = bean,
                handlerInterface = OutboxTypedHandler::class.java,
                methodName = "handle",
                contextType = OutboxRecordMetadata::class.java,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("does not implement ${OutboxTypedHandler::class.java.name}")
    }

    @Test
    fun `resolveInterfacePayloadType resolves inherited generic argument`() {
        val bean = InheritedTypedHandler()

        val result = ReflectionUtils.resolveInterfacePayloadType(bean, OutboxTypedHandler::class.java)

        assertThat(result).isEqualTo(HandlerPayload::class.java)
    }

    @Test
    fun `findInterfaceMethod resolves inherited generic method without returning bridge method`() {
        val bean = InheritedTypedHandler()

        val result =
            ReflectionUtils.findInterfaceMethod(
                bean = bean,
                handlerInterface = OutboxTypedHandler::class.java,
                methodName = "handle",
                contextType = OutboxRecordMetadata::class.java,
            )

        assertThat(result.isBridge).isFalse()
        assertThat(result.isSynthetic).isFalse()
    }

    @Test
    fun `findInterfaceMethod sets accessible on method in package-private class`() {
        val bean = OverloadedTypedHandler()
        val method =
            ReflectionUtils.findInterfaceMethod(
                bean = bean,
                handlerInterface = OutboxTypedHandler::class.java,
                methodName = "handle",
                contextType = OutboxRecordMetadata::class.java,
            )

        assertThat(method.canAccess(bean)).isTrue()
    }

    @Test
    fun `findAnnotatedMethods finds all methods with annotation`() {
        val bean = TestAnnotatedBean()

        val result = ReflectionUtils.findAnnotatedMethods(bean, TestAnnotation::class.java).toList()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.name }).containsExactlyInAnyOrder("annotatedMethod1", "annotatedMethod2")
    }

    @Test
    fun `findAnnotatedMethods returns empty sequence when no annotated methods found`() {
        val bean = TestBean()

        val result = ReflectionUtils.findAnnotatedMethods(bean, TestAnnotation::class.java).toList()

        assertThat(result).isEmpty()
    }

    @Test
    fun `findAnnotatedMethods finds methods on proxy`() {
        val target = TestAnnotatedBean()
        val proxyFactory = ProxyFactory(target)
        proxyFactory.isProxyTargetClass = true
        val proxy = proxyFactory.proxy

        val result = ReflectionUtils.findAnnotatedMethods(proxy, TestAnnotation::class.java).toList()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.name }).containsExactlyInAnyOrder("annotatedMethod1", "annotatedMethod2")
    }

    @Test
    fun `findAnnotatedMethods filters out bridge and synthetic methods`() {
        val bean = TestAnnotatedBean()

        val result = ReflectionUtils.findAnnotatedMethods(bean, TestAnnotation::class.java).toList()

        assertThat(result).allMatch { !it.isBridge && !it.isSynthetic }
    }

    @Test
    fun `findAnnotatedMethods returns sequence for lazy evaluation`() {
        val bean = TestAnnotatedBean()

        val result = ReflectionUtils.findAnnotatedMethods(bean, TestAnnotation::class.java)

        assertThat(result).isInstanceOf(Sequence::class.java)
    }

    @Test
    fun `findAnnotatedMethods finds inherited annotated methods`() {
        val bean = TestChildBean()

        val result = ReflectionUtils.findAnnotatedMethods(bean, TestAnnotation::class.java).toList()

        assertThat(result).hasSizeGreaterThanOrEqualTo(2)
        assertThat(result.map { it.name }).contains("annotatedMethod1", "annotatedMethod2")
    }

    @Test
    fun `findAnnotatedMethods sets accessible on public annotated method in package-private class`() {
        class PackagePrivateAnnotatedClass {
            @TestAnnotation("pkg-private")
            fun exposed() {}
        }
        val bean = PackagePrivateAnnotatedClass()
        val methods = ReflectionUtils.findAnnotatedMethods(bean, TestAnnotation::class.java).toList()
        assertThat(methods).hasSize(1)

        val method = methods.first()
        assertThat(method.canAccess(bean)).isTrue()
    }

    @Test
    fun `findAnnotatedMethods finds all Java package-private methods`() {
        val bean = JavaPackagePrivateAnnotatedBean()

        val result = ReflectionUtils.findAnnotatedMethods(bean, TestJavaAnnotation::class.java).toList()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.name }).containsExactlyInAnyOrder("annotatedMethod1", "annotatedMethod2")
    }

    // Test beans
    @Suppress("UNUSED_PARAMETER")
    open class TestBean {
        fun methodWithOneParam(param: String) {}

        fun methodWithTwoParams(
            param1: String,
            param2: Int,
        ) {
        }

        fun methodWithNoParams() {}
    }

    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class TestAnnotation(
        val value: String = "",
    )

    interface TestHandlerInterface

    class TestHandlerImpl : TestHandlerInterface

    private class OverloadedTypedHandler : OutboxTypedHandler<HandlerPayload> {
        override fun handle(
            payload: HandlerPayload,
            metadata: OutboxRecordMetadata,
        ) = Unit

        fun handle(
            payload: AlternativePayload,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private abstract class GenericTypedHandler<T> : OutboxTypedHandler<T> {
        override fun handle(
            payload: T,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class InheritedTypedHandler : GenericTypedHandler<HandlerPayload>()

    private class HandlerPayload

    private class AlternativePayload

    @Suppress("UNUSED_PARAMETER")
    open class TestAnnotatedBean {
        @TestAnnotation("test-handler-1")
        fun annotatedMethod1(param: Any) {}

        @TestAnnotation("test-handler-2")
        fun annotatedMethod2(param: String) {}

        fun nonAnnotatedMethod() {}
    }

    open class TestChildBean : TestAnnotatedBean()
}
