rootProject.name = "namastack-outbox-performance-test"

// Benchmark this checkout rather than potentially stale Maven Local snapshots.
includeBuild("..")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include("consumer")
include("tooling")
