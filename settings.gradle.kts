plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "spring-outbox"

include("spring-outbox-core")
include("spring-outbox-jpa")
