plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `maven-publish`
    jacoco
}

description = "spring-outbox-core"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.6"))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-logging")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("io.mockk:mockk:1.13.8")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = "com.beisel"
            artifactId = "spring-outbox-core"
            version = project.version.toString()

            pom {
                name.set("Spring Outbox Core")
                description.set("Core components for Spring Outbox")
            }
        }
    }
}
