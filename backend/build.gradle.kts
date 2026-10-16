plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}
repositories { mavenCentral() }
val ktor = "3.1.3"
dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.3")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("de.mkammerer:argon2-jvm:2.11")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.flywaydb:flyway-core:10.22.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.22.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
kotlin { jvmToolchain(17) }
application { mainClass.set("fsm.ApplicationKt") }
tasks.test {
    useJUnitPlatform { excludeTags("postgres") }
}
val integrationTest by tasks.registering(Test::class) {
    description = "Real PostgreSQL tests; requires TEST_DATABASE_URL/USER/PASSWORD. Uses a disposable schema."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    shouldRunAfter(tasks.test)
}
