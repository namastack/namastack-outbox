plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform("io.namastack:namastack-outbox-bom:${project.version}"))

    implementation("io.namastack:namastack-outbox-starter-jdbc")
    implementation("io.namastack:namastack-outbox-observability")
    implementation("io.namastack:namastack-outbox-actuator")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.jackson.module.kotlin)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly(libs.postgresql)
}

tasks.bootJar {
    archiveFileName.set("namastack-outbox-performance-test-consumer.jar")
}
