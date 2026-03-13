package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import org.aopalliance.intercept.MethodInterceptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory

@DisplayName("BaseHandlerMethod - CGLIB Proxy ID Stability")
class BaseHandlerMethodProxyTest {
    /**
     * This test verifies that buildId() produces a stable handler ID
     * regardless of whether the bean is wrapped in a CGLIB proxy.
     *
     * When @Transactional or other AOP annotations are applied,
     * Spring wraps the bean in a CGLIB proxy. The handler ID stored
     * in the database must use the original target class name,
     * not the proxy class name (e.g., MyHandler$$SpringCGLIB$$0).
     */
    @Test
    fun `handler ID should use target class name not CGLIB proxy class name`() {
        val targetBean = TestHandler()
        val method = TestHandler::class.java.getMethod("handle", String::class.java)

        // Create a CGLIB proxy (simulates @Transactional behavior)
        val proxyFactory = ProxyFactory(targetBean)
        proxyFactory.isProxyTargetClass = true
        proxyFactory.addAdvice(MethodInterceptor { it.proceed() })
        val proxiedBean = proxyFactory.proxy

        // Create handlers from both the original bean and the proxied bean
        val handlerFromTarget = TypedHandlerMethod(targetBean, method)
        val handlerFromProxy = TypedHandlerMethod(proxiedBean, method)

        // The handler ID should be identical regardless of proxy wrapping
        // If buildId() uses bean::class.java.name directly, the proxy will produce
        // a different ID like "TestHandler$$SpringCGLIB$$0#handle(java.lang.String)"
        assertThat(handlerFromProxy.id)
            .describedAs(
                "Handler ID from proxied bean should match target bean's ID. " +
                    "Actual proxy ID: ${handlerFromProxy.id}, " +
                    "Expected target ID: ${handlerFromTarget.id}",
            ).isEqualTo(handlerFromTarget.id)
    }

    @Test
    fun `handler ID should contain original class name not proxy class name`() {
        val targetBean = TestHandler()
        val method = TestHandler::class.java.getMethod("handle", String::class.java)

        // Create a CGLIB proxy
        val proxyFactory = ProxyFactory(targetBean)
        proxyFactory.isProxyTargetClass = true
        proxyFactory.addAdvice(MethodInterceptor { it.proceed() })
        val proxiedBean = proxyFactory.proxy

        val handlerFromProxy = TypedHandlerMethod(proxiedBean, method)

        // ID should contain the original class name
        assertThat(handlerFromProxy.id).contains("TestHandler")
        // ID should NOT contain CGLIB proxy indicators
        assertThat(handlerFromProxy.id).doesNotContain("CGLIB")
        assertThat(handlerFromProxy.id).doesNotContain("$$")
    }

    open class TestHandler {
        open fun handle(payload: String) {
            // no-op
        }
    }
}
