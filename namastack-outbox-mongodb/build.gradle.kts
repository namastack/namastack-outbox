plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-mongodb"

dependencies {
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.data.mongodb)
    implementation(libs.spring.tx)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(project(":namastack-outbox-jackson"))
    testImplementation(libs.spring.boot.starter.data.mongodb.test)
    testImplementation(libs.mockk)
    testImplementation(libs.jackson.module.kotlin)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)

    testRuntimeOnly(libs.junit.platform.launcher)
}
