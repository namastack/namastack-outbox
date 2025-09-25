package com.beisel.springoutbox

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

@TestConfiguration
class PostgresTestContainerConfig {
    companion object {
        private const val DB_IMAGE = "postgres:17.5"
        private const val DB_NAME = "test-db"
        private const val DB_USER = "test-user"
        private const val DB_PASS = "test-pass"

        private val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DB_IMAGE).apply {
                withDatabaseName(DB_NAME)
                withUsername(DB_USER)
                withPassword(DB_PASS)
                start()
            }
    }

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<Nothing> = postgres
}
