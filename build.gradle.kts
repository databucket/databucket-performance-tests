plugins {
    java
    id("io.gatling.gradle") version "3.16.1"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Settings of the tests: -Pname=value (see README and src/gatling/java/pl/databucket/perf/PerfConfig.java)
val settings = listOf(
    "baseUrl", "project", "bucket", "admin", "testers", "testerPrefix", "password",
    "users", "rampUp", "duration", "startJitter", "mix", "delayMin", "delayMax",
    "rules", "sort", "p95", "maxFailedPercent",
    "superUser", "superPassword", "rows", "history", "reservedPercent",
)

fun forwardedSettings(): Map<String, Any> =
    settings.mapNotNull { name -> providers.gradleProperty(name).orNull?.let { "perf.$name" to it } }.toMap()

tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    systemProperties = forwardedSettings()
}

// Creates the project, its user and the bucket with test data (idempotent): ./gradlew prepareData -Prows=100000
tasks.register<JavaExec>("prepareData") {
    group = "gatling"
    description = "Creates the project, admin, tester accounts and bucket, fills it up to -Prows rows, reserves -PreservedPercent"
    classpath = sourceSets["gatling"].runtimeClasspath
    mainClass = "pl.databucket.perf.setup.PrepareData"
    systemProperties(forwardedSettings())
}
