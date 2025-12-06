plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-outbox-starter-jpa"

dependencies {
    api(project(":namastack-outbox-api"))
    api(project(":namastack-outbox-core"))
    api(project(":namastack-outbox-jpa"))
    api(project(":namastack-outbox-jackson"))
}
