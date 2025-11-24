plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    jacoco
}

description = "namastack-outbox-integration-tests"

dependencies {
    testImplementation(project(":namastack-outbox-core"))
    testImplementation(project(":namastack-outbox-jpa"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
