plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "spring-outbox"

include("spring-outbox-core")
include("spring-outbox-jpa")
