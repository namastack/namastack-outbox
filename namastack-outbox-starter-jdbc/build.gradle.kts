plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-outbox-starter-jdbc"

dependencies {
    api(project(":namastack-outbox-api"))
    api(project(":namastack-outbox-core"))
    api(project(":namastack-outbox-jdbc"))
    api(project(":namastack-outbox-jackson"))
}
