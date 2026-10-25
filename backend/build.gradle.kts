// Backend build (ADR-0002, STORY-002 design notes). Versions are pinned inline on purpose: one module, no catalog.
import com.github.spotbugs.snom.Confidence
import java.time.Instant
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Plugin classpath is locked too (-> buildscript-gradle.lockfile). Boot 4.1.2's plugin ships fixed httpclient5/httpcore5.
buildscript {
    dependencyLocking { lockAllConfigurations() }
    dependencies {
        constraints {
            // GHSA-j288-q9x7-2f5v: spring-boot-gradle-plugin 4.1.2 -> buildpack-platform -> commons-compress 1.27.1 -> commons-lang3 3.16.0
            classpath("org.apache.commons:commons-lang3:3.21.0")
        }
    }
}

plugins {
    java
    jacoco
    checkstyle
    id("org.springframework.boot") version "4.1.2"
    id("net.ltgt.errorprone") version "5.1.1"
    id("com.diffplug.spotless") version "8.10.4"
    id("com.github.spotbugs") version "6.5.13"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.openapi.generator") version "7.26.0"
}

group = "eg.masroof"
version = "0.1.0" // STORY-143 reads this for /health

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

// Lock every resolvable configuration so SCA (osv-scanner, Dependabot) sees the full graph (CWE-1104, ASVS 15.1.2).
// Refresh with: ./gradlew dependencies buildEnvironment --write-locks
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation") // generated models carry jakarta.validation constraints
    implementation("org.jspecify:jspecify")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    errorprone("com.google.errorprone:error_prone_core:2.51.0")
    errorprone("com.uber.nullaway:nullaway:0.14.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// /health build info (STORY-143): the Docker build passes -PgitSha/-PbuildTime; local builds read git, so checkFast needs no env.
val gitSha = providers.gradleProperty("gitSha").orElse(
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim().take(7) })
val buildTime = providers.gradleProperty("buildTime").orElse( // commit time keeps bootBuildInfo up to date between builds
    providers.exec { commandLine("git", "show", "-s", "--format=%ct", "HEAD") }.standardOutput.asText
        .map { Instant.ofEpochSecond(it.trim().toLong()).toString() })
springBoot { buildInfo { this.properties { time = buildTime; additional.put("gitSha", gitSha) } } }
tasks.bootJar { archiveFileName = "masroof-backend.jar" }
tasks.jar { enabled = false }

// REQ-OPS-050: Spring server interfaces + models generated from the contract at build time, never edited or committed.
// Generated code lives in eg.masroof.adapters.in.rest.generated.*; contract Money maps to the domain Money, date-time to Instant.
val openApiOut = layout.buildDirectory.dir("generated/openapi")
openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(layout.projectDirectory.file("../contracts/openapi.yaml").asFile.absolutePath)
    outputDir.set(openApiOut.get().asFile.absolutePath)
    apiPackage.set("eg.masroof.adapters.in.rest.generated.api")
    modelPackage.set("eg.masroof.adapters.in.rest.generated.model")
    invokerPackage.set("eg.masroof.adapters.in.rest.generated")
    globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to "false"))
    typeMappings.set(mapOf("DateTime" to "Instant", "Money" to "Money"))
    importMappings.set(mapOf("Instant" to "java.time.Instant", "Money" to "eg.masroof.domain.money.Money"))
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot4" to "true",
        "useTags" to "true",
        "skipDefaultInterface" to "true",
        "useBeanValidation" to "true",
        "performBeanValidation" to "false",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "openApiNullable" to "false",
        "hideGenerationTimestamp" to "true",
        "useResponseEntity" to "true",
        "useOptional" to "false",
        "dateLibrary" to "java8",
    ))
}
tasks.openApiGenerate {
    doFirst { delete(openApiOut) } // regenerated from scratch on every run
    doLast { // the generator emits Spring's deprecated @Nullable (-Werror); the build swaps it for JSpecify's, as everywhere else
        fileTree(openApiOut).matching { include("**/*.java") }.forEach { f ->
            f.writeText(f.readText().replace("import org.springframework.lang.Nullable;", "import org.jspecify.annotations.Nullable;"))
        }
    }
}
sourceSets.main { java.srcDir(openApiOut.map { it.dir("src/main/java") }) }
tasks.compileJava { dependsOn(tasks.openApiGenerate) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*" // openapi output is not ours to lint
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
tasks.checkstyleMain { exclude("**/generated/**") }
tasks.checkstyleTest { enabled = false }

spotbugs { reportLevel = Confidence.HIGH }
tasks.spotbugsTest { enabled = false }

tasks.test {
    useJUnitPlatform { excludeTags("integration", "migration", "arch") }
    finalizedBy(tasks.jacocoTestReport)
}

// Under -q a failing test prints its class, the assertion and the causes (default quiet logging shows only the summary).
tasks.withType<Test>().configureEach {
    testLogging.quiet {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
    }
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
    .configure { extensions.configure<JacocoTaskExtension> { isEnabled = false } } // coverage comes from integrationTest

jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport {
    // Merges unit and integration runs; the XML lands in build/reports/jacoco/test/jacocoTestReport.xml.
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("test.exec", "integrationTest.exec"))
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude("eg/masroof/MasroofApplication.class", "eg/masroof/adapters/in/rest/generated/**") }
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
