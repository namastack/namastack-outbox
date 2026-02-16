plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-core"

dependencies {
    implementation(project(":namastack-outbox-api"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.tx)
    implementation(libs.commons.codec)
    implementation(libs.kotlin.reflect)

    compileOnly(libs.jakarta.annotation.api)
    compileOnly(libs.slf4j.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
