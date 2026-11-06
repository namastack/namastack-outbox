plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.postgresql:postgresql")
    implementation("com.mysql:mysql-connector-j")
    implementation("org.mariadb.jdbc:mariadb-java-client")
    implementation("com.microsoft.sqlserver:mssql-jdbc")
    implementation("com.oracle.database.jdbc:ojdbc11")
    implementation("com.h2database:h2")
    implementation("commons-codec:commons-codec")
}

application {
    mainClass.set("bench.MainKt")
    applicationName = "bench"
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC", "-Xmx3g")
}
