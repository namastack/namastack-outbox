package io.namastack.outbox.handler.scanner.handler

import io.namastack.outbox.HandlerBeanFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InterfaceHandlerScannerTest {
    private val scanner = InterfaceHandlerScanner()

    @Test
    fun `uses bean name as handler ID for lambda`() {
        val handler = scanner.scan(LambdaOutboxHandlerFactory.create(), "modulithEventExternalizer").single().handler

        assertThat(handler.id).isEqualTo("modulithEventExternalizer")
    }

    @Test
    fun `uses bean name as handler ID for typed lambda`() {
        val handler = scanner.scan(LambdaOutboxHandlerFactory.createTyped(), "typedEventExternalizer").single().handler

        assertThat(handler.id).isEqualTo("typedEventExternalizer")
    }

    @Test
    fun `keeps generated handler ID for regular interface implementation`() {
        val bean = HandlerBeanFactory.createGenericInterfaceHandler()
        val handler = scanner.scan(bean, "genericInterfaceHandler").single().handler

        assertThat(handler.id)
            .startsWith(bean::class.java.name)
            .contains("#handle(")
            .isNotEqualTo("genericInterfaceHandler")
    }
}
