rootProject.name = "namastack-outbox-performance-test"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include("consumer")
include("tooling")
