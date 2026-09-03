plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-jdbc"

dependencies {
    api(project(":namastack-outbox-api"))
    api(project(":namastack-outbox-core"))

    api(platform(libs.spring.boot.bom))
    api(libs.spring.jdbc)
    api(libs.spring.tx)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.jdbc)

    testImplementation(project(":namastack-outbox-jackson"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jdbc.test)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(libs.jackson.module.kotlin)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.oracle.xe)

    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.mssql.jdbc)
    testRuntimeOnly(libs.oracle.jdbc)
}
