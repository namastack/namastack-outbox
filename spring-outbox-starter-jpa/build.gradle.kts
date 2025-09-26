plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "spring-outbox-starter-jpa"

dependencies {
    api("com.beisel:spring-outbox-core:0.0.1-SNAPSHOT")
    api("com.beisel:spring-outbox-jpa:0.0.1-SNAPSHOT")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = "com.beisel"
            artifactId = "spring-outbox-starter-jpa"
            version = project.version.toString()

            pom {
                name.set("Spring Outbox JPA Starter")
                description.set("JPA Starter for Spring Outbox")
            }
        }
    }
}
