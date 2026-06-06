plugins {
    `java-platform`
}

description = "Namastack Outbox BOM"

dependencies {
    constraints {
        api(project(":namastack-outbox-actuator"))
        api(project(":namastack-outbox-api"))
        api(project(":namastack-outbox-core"))
        api(project(":namastack-outbox-jackson"))
        api(project(":namastack-outbox-jdbc"))
        api(project(":namastack-outbox-jpa"))
        api(project(":namastack-outbox-kafka"))
        api(project(":namastack-outbox-metrics"))
        api(project(":namastack-outbox-mongodb"))
        api(project(":namastack-outbox-observability-api"))
        api(project(":namastack-outbox-observability"))
        api(project(":namastack-outbox-rabbit"))
        api(project(":namastack-outbox-sns"))
        api(project(":namastack-outbox-starter-jdbc"))
        api(project(":namastack-outbox-starter-jpa"))
        api(project(":namastack-outbox-starter-mongodb"))
        api(project(":namastack-outbox-tracing"))
    }
}
