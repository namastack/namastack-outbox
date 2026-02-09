plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-rabbit"

dependencies {
    implementation(project(":namastack-outbox-api"))

    compileOnly(platform(libs.spring.boot.bom))
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.amqp)
    compileOnly(libs.spring.rabbit)
    compileOnly(libs.spring.boot.amqp)
    compileOnly(libs.jackson.databind)
    compileOnly(libs.jackson.module.kotlin)
    compileOnly(libs.slf4j.api)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.amqp)
    testImplementation(libs.spring.rabbit)
    testImplementation(libs.spring.boot.amqp)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
