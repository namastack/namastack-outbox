plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-jdbc"

dependencies {
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)

    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.jdbc)
    compileOnly(libs.spring.boot)

    testImplementation(project(":namastack-outbox-jackson"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.data.jdbc)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
