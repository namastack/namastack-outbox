package io.namastack.demo

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

internal class ExamplePostgresqlContainer : PostgreSQLContainer("postgres:18-alpine") {
    init {
        withDatabaseName("outbox_example")
        withUsername("username")
        withPassword("password")
    }

    fun registerDataSource(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { jdbcUrl }
        registry.add("spring.datasource.username") { username }
        registry.add("spring.datasource.password") { password }
    }
}
