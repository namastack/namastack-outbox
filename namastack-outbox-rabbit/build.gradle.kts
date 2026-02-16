plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-rabbit"

dependencies {
    implementation(project(":namastack-outbox-api"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.amqp)
    implementation(libs.spring.rabbit)
    implementation(libs.spring.boot.amqp)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    compileOnly(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
