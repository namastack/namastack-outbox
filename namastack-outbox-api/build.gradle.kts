plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-outbox-api"

dependencies {
    compileOnly(platform(libs.spring.boot.bom))
    compileOnly(libs.spring.core)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
