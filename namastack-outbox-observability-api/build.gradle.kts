plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

description = "namastack-outbox-observability-api"

dependencies {
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.micrometer.observation)
}
