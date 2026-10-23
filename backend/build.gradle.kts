// Backend build (ADR-0002, STORY-002 design notes). Versions are pinned inline on purpose: one module, no catalog.
import com.github.spotbugs.snom.Confidence
import net.ltgt.gradle.errorprone.errorprone
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    checkstyle
    id("org.springframework.boot") version "3.5.16"
    id("net.ltgt.errorprone") version "5.1.1"
    id("com.diffplug.spotless") version "8.10.4"
    id("com.github.spotbugs") version "6.5.13"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "eg.masroof"
version = "0.1.0" // STORY-143 reads this for /health

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform("org.junit:junit-bom:5.14.4")) // > Boot's 5.12.2; jqwik 1.10.2 needs platform 1.14
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.jspecify:jspecify:1.0.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    errorprone("com.google.errorprone:error_prone_core:2.51.0")
    errorprone("com.uber.nullaway:nullaway:0.14.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation("net.jqwik:jqwik:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        error("NullAway", "RemoveUnusedImports")
        option("NullAway:AnnotatedPackages", "eg.masroof")
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.36.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "14.3.0"
    configFile = file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
    configProperties["org.checkstyle.google.severity"] = "error" // ERROR-level lines stay visible under -q
}
tasks.checkstyleTest { enabled = false }

spotbugs { reportLevel = Confidence.HIGH }
tasks.spotbugsTest { enabled = false }

tasks.test {
    useJUnitPlatform { excludeTags("integration", "migration", "arch") }
    finalizedBy(tasks.jacocoTestReport)
}

fun taggedTest(name: String, tag: String, what: String) = tasks.register<Test>(name) {
    description = what
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags(tag) }
    shouldRunAfter(tasks.test)
}

val integrationTest = taggedTest("integrationTest", "integration", "Testcontainers tests (tag integration)")
integrationTest { finalizedBy(tasks.jacocoTestReport) }
taggedTest("archTest", "arch", "ArchUnit rules over main classes (tag arch)")
    .configure { extensions.configure<JacocoTaskExtension> { isEnabled = false } }
taggedTest("migrationTest", "migration", "Flyway from empty (previous-snapshot case arrives with STORY-004)")

jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport {
    // Merges unit and integration runs; the XML lands in build/reports/jacoco/test/jacocoTestReport.xml.
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("test.exec", "integrationTest.exec"))
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude("eg/masroof/MasroofApplication.class") }
    )
    reports {
        xml.required = true
        html.required = true
    }
    mustRunAfter(integrationTest)
}

pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf(
        "eg.masroof.domain.*", "eg.masroof.*.money.*", "eg.masroof.*.parsing.*",
        "eg.masroof.*.dedup.*", "eg.masroof.*.analytics.*",
    )
    excludedGroups = setOf("integration", "migration", "arch")
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false // -> build/reports/pitest/mutations.xml
    failWhenNoMutations = false // the skeleton has no mutable domain code yet
}

tasks.register("checkFast") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "S8 fast gate: format, lint, -Werror compile with Error Prone + NullAway, SpotBugs HIGH, unit tests"
    dependsOn("spotlessCheck", "checkstyleMain", "compileJava", "compileTestJava", "spotbugsMain", "test")
}
