plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    jacoco
}

description = "namastack-outbox-jpa"

dependencies {
    compileOnly(libs.hibernate.core)
    compileOnly(libs.spring.orm)

    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.hibernate)
    compileOnly(libs.spring.boot)

    testImplementation(project(":namastack-outbox-jackson"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
