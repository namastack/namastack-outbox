plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

// Compile against the jars of one worktree (default: main). Run against any worktree via bin/repo-bench,
// which prepends that worktree's namastack-outbox-*.jar files to the classpath.
// The jar list is written by bin/variant-build.sh to build/variants/<variant>.jars
val compileJars: String = (findProperty("harness.compileJars") as String?) ?: "build/variants/main.jars"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    val listFile = rootProject.file(compileJars)
    if (listFile.exists()) {
        compileOnly(files(listFile.readText().trim().split(":").filter { it.isNotBlank() }))
    }
    implementation(project(":sql"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-orm")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-context")
    implementation("org.hibernate.orm:hibernate-core")
    implementation("jakarta.persistence:jakarta.persistence-api")
    implementation("com.zaxxer:HikariCP")
    implementation("org.postgresql:postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("commons-codec:commons-codec")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application {
    mainClass.set("benchrepo.MainKt")
    applicationName = "repo-bench"
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC", "-Xmx2g")
}
