plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-tracing"

dependencies {
    api(project(":namastack-outbox-observability"))
}

tasks.configureEach {
    doFirst {
        logger.warn("'namastack-outbox-tracing' is deprecated. Use 'namastack-outbox-observability")
    }
}
