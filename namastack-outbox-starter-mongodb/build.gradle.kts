plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "namastack-outbox-starter-mongodb"

dependencies {
    api(project(":namastack-outbox-api"))
    api(project(":namastack-outbox-core"))
    api(project(":namastack-outbox-mongodb"))
    api(project(":namastack-outbox-jackson"))
}
