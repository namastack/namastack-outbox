package io.namastack.performance.tooling.report

internal data class EnvironmentInfo(
    val gitCommit: String,
    val gitDirty: Boolean,
    val javaVersion: String,
    val operatingSystem: String,
    val dockerVersion: String,
    val postgresVersion: String,
) {
    fun toMap() = mapOf("gitCommit" to gitCommit, "gitDirty" to gitDirty, "javaVersion" to javaVersion, "operatingSystem" to operatingSystem, "dockerVersion" to dockerVersion, "postgresVersion" to postgresVersion)
}
