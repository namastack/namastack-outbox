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

    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.h2)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
