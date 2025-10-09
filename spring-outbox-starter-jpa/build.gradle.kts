plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "spring-outbox-starter-jpa"

dependencies {
    api("io.namastack:spring-outbox-core:${project.version}")
    api("io.namastack:spring-outbox-jpa:${project.version}")
}
