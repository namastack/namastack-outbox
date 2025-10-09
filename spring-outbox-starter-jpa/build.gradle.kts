plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "spring-outbox-starter-jpa"

dependencies {
    api(project(":spring-outbox-core"))
    api(project(":spring-outbox-jpa"))
}
