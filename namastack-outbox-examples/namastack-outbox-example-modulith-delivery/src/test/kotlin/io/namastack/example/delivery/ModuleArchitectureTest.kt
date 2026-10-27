package io.namastack.example.delivery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModuleArchitectureTest {
    @Test
    fun `modules respect their declared dependencies`() {
        val modules = ApplicationModules.of(DeliveryApplication::class.java)

        modules.verify()

        assertThat(modules.map { it.identifier.toString() })
            .containsExactlyInAnyOrder("orders", "billing", "notifications")
    }
}
