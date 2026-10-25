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
    id("org.openapi.generator") version "7.14.0"
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
    implementation("org.springframework.boot:spring-boot-starter-validation")
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

// Server interfaces + models from the contract (STORY-006, REQ-OPS-050). Generated code is never edited or committed.
val openApiOut = layout.buildDirectory.dir("generated/openapi")
openApiGenerate {
    generatorName = "spring"
    inputSpec = rootProject.file("../contracts/openapi.yaml").toURI().toString()
    outputDir = openApiOut.map { it.asFile.absolutePath }
    apiPackage = "eg.masroof.adapters.in.rest.generated.api"
    modelPackage = "eg.masroof.adapters.in.rest.generated.model"
    invokerPackage = "eg.masroof.adapters.in.rest.generated"
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "ApiUtil.java")
    typeMappings = mapOf("DateTime" to "Instant")
    schemaMappings = mapOf("Money" to "Money")
    importMappings = mapOf("Instant" to "java.time.Instant", "Money" to "eg.masroof.domain.money.Money")
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "useTags" to "true",
        "useSpringBoot3" to "true",
        "useBeanValidation" to "true",
        "performBeanValidation" to "false",
        "openApiNullable" to "false",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "hideGenerationTimestamp" to "true",
        "useOptional" to "false",
        "skipDefaultInterface" to "false",
        "generatedConstructorWithRequiredArgs" to "false",
    )
}
sourceSets.main { java.srcDir(openApiOut.map { it.dir("src/main/java") }) }
tasks.compileJava { dependsOn(tasks.openApiGenerate) }
// Spring 7 deprecates org.springframework.lang.Nullable (the generator template imports it); the build fails on warnings.
tasks.openApiGenerate {
    doLast {
        openApiOut.get().asFile.walkTopDown().filter { it.extension == "java" }.forEach {
            it.writeText(it.readText().replace("import org.springframework.lang.Nullable;", "import org.jspecify.annotations.Nullable;"))
        }
    }
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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
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
tasks.checkstyleMain { exclude("**/generated/**") }

spotbugs {
    reportLevel = Confidence.HIGH
    excludeFilter = file("config/spotbugs/exclude.xml") // generated OpenAPI code is not ours to fix
}
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
        sourceSets.main.get().output.classesDirs.asFileTree.matching {
            exclude("eg/masroof/MasroofApplication.class", "**/rest/generated/**") // generated code is not ours to cover
        }
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
