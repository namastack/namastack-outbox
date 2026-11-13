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

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.mariadb.java.client)
    runtimeOnly(libs.mssql.jdbc)
    runtimeOnly(libs.oracle.jdbc)
    runtimeOnly(libs.h2)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.bootJar {
    archiveFileName.set("namastack-outbox-performance-test-consumer.jar")
}
