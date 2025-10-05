plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "spring-outbox-starter-jpa"

dependencies {
    api("io.namastack:spring-outbox-core:0.0.1-SNAPSHOT")
    api("io.namastack:spring-outbox-jpa:0.0.1-SNAPSHOT")
}
