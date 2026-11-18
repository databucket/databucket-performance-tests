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

// Keeps the last report with the settings of its run: ./gradlew keepReport -Pname=2026-11-18-1m/a-no-indexes
// → results/<name>/report/index.html and results/<name>/settings.txt (add a README.md there to describe the run)
tasks.register("keepReport") {
    group = "gatling"
    description = "Copies the last Gatling report and its settings to results/<-Pname>"
    val runName = providers.gradleProperty("name")
    val reports = layout.buildDirectory.dir("reports/gatling")
    val results = layout.projectDirectory.dir("results")
    doLast {
        val target = results.dir(runName.orNull ?: throw GradleException("-Pname=<folder> needed")).asFile
        val dir = reports.get().asFile
        val latest = dir.listFiles { f -> f.isDirectory }?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No Gatling report in $dir")
        latest.copyRecursively(target.resolve("report"), overwrite = true)
        dir.resolve("last-run.txt").takeIf { it.exists() }?.copyTo(target.resolve("settings.txt"), overwrite = true)
        println("Kept ${latest.name} in $target")
    }
}
