plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-outbox-starter-insights"

dependencies {
    api(project(":namastack-outbox-actuator"))
    api(project(":namastack-outbox-metrics"))
    api(project(":namastack-outbox-tracing"))
}
