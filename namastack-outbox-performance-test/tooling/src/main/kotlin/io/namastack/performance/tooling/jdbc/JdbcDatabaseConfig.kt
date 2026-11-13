package io.namastack.performance.tooling.jdbc

internal data class JdbcDatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)
