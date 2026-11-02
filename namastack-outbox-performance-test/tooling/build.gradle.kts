plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform("io.namastack:namastack-outbox-bom:${project.version}"))
    implementation("io.namastack:namastack-outbox-core")
    implementation("io.namastack:namastack-outbox-jdbc")
    implementation("io.namastack:namastack-outbox-jackson")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.mariadb.java.client)
    runtimeOnly(libs.mssql.jdbc)
    runtimeOnly(libs.oracle.jdbc)
    runtimeOnly(libs.h2)
    implementation(libs.postgresql)
    implementation(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.namastack.performance.tooling.MainKt")
}
