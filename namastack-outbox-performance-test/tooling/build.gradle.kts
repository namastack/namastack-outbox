plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform("io.namastack:namastack-outbox-bom:${project.version}"))
    implementation("io.namastack:namastack-outbox-core")
    implementation(libs.postgresql)
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("io.namastack.performance.tooling.MainKt")
}
