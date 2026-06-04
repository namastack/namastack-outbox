plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("io.namastack:namastack-outbox-core:${project.version}")
    implementation(libs.postgresql)
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("io.namastack.performance.tooling.MainKt")
}
