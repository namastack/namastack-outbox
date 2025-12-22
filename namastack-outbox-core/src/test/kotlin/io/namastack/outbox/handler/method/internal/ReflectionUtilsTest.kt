package io.namastack.outbox.handler.method.internal

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
    fun `findMethod finds method by name and parameter count`() {
        val bean = TestBean()

        val result = ReflectionUtils.findMethod(bean, "methodWithTwoParams", 2)

        assertThat(result).isNotNull
        assertThat(result.name).isEqualTo("methodWithTwoParams")
        assertThat(result.parameterCount).isEqualTo(2)
    }

    @Test
    fun `findMethod finds method on proxy`() {
        val target = TestBean()
        val proxyFactory = ProxyFactory(target)
        proxyFactory.isProxyTargetClass = true
        val proxy = proxyFactory.proxy

        val result = ReflectionUtils.findMethod(proxy, "methodWithTwoParams", 2)

        assertThat(result).isNotNull
        assertThat(result.name).isEqualTo("methodWithTwoParams")
        assertThat(result.parameterCount).isEqualTo(2)
    }

    @Test
    fun `findMethod throws exception when method not found`() {
        val bean = TestBean()

        assertThatThrownBy {
            ReflectionUtils.findMethod(bean, "nonExistentMethod", 2)
        }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `findMethod throws exception when parameter count does not match`() {
        val bean = TestBean()

        assertThatThrownBy {
            ReflectionUtils.findMethod(bean, "methodWithTwoParams", 1)
        }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `findMethod filters out bridge and synthetic methods`() {
        val bean = TestBean()

        val result = ReflectionUtils.findMethod(bean, "methodWithOneParam", 1)

        assertThat(result).isNotNull
        assertThat(result.isBridge).isFalse()
        assertThat(result.isSynthetic).isFalse()
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
