plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    jacoco
}

description = "namastack-outbox-jpa"

dependencies {
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.hibernate)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(project(":namastack-outbox-jackson"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(libs.jackson.module.kotlin)
    testRuntimeOnly(libs.junit.platform.launcher)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
