plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// Lock only what ships in the APK (runtime classpaths); tooling/test classpaths are intentionally not locked, so
// osv-scanner reports on shipped code. LENIENT: a few artifacts differ per OS. Refresh: ./gradlew :app:dependencies --write-locks
configurations
    .matching { it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath" }
    .configureEach { resolutionStrategy.activateDependencyLocking() }
dependencyLocking { lockMode.set(LockMode.LENIENT) }

kover {
    reports {
        filters {
            excludes {
                // Generated glue (Hilt/Dagger/Room/Compose) is not ours to cover.
                classes(
                    "*_Factory",
                    "*_Factory\$*",
                    "*Hilt_*",
                    "*_HiltModules*",
                    "*_MembersInjector",
                    "dagger.hilt.*",
                    "hilt_aggregated_deps.*",
                )
                classes("*_Impl", "*_Impl\$*", "*ComposableSingletons*", "eg.masroof.BuildConfig", "eg.masroof.R", "eg.masroof.R\$*")
                classes("eg.masroof.ui.theme.*", "eg.masroof.MainActivity*", "eg.masroof.MasroofApp*")
            }
        }
    }
}

android {
    namespace = "eg.masroof"
    compileSdk = 36

    defaultConfig {
        applicationId = "eg.masroof"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only the two shipped locales; keeps the APK small and lint's MissingTranslation honest.
        resourceConfigurations += listOf("en", "ar")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        // Version-bump nags are not defects; dependency upgrades are deliberate (version catalog).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }

    // In-app language switch: never split resources by language (also keeps lint's AppBundleLocaleChanges honest).
    bundle {
        language { enableSplit = false }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric Compose tests need the debug-only ui-test-manifest (it registers ComponentActivity), so they cannot run
// against the release variant (minify is off: same code, same tests). Unit tests run once, on debug.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

ktlint {
    version.set("1.5.0")
    android.set(true)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    source.setFrom("src/main/java", "src/test/java")
}

// detekt 1.23.x is compiled against Kotlin 2.0.x; pin its Kotlin artifacts so it ignores the project's Kotlin version.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}

dependencies {
    implementation(project(":api-client"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

// Money must never be a floating point type (PROMPT money invariant). detekt 1.23 has no built-in type ban, so this
// task scans production sources: any Float/Double usage is a failure unless it is clearly UI geometry/typography.
val checkNoFloatMoney by tasks.registering {
    group = "verification"
    description = "Fails if Float/Double appears in money-related production code"
    val sources = fileTree("src/main/java") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val floating = Regex("""\b(Double|Float)\b|\.to(Double|Float)\(|\b\d+\.\d+f?\b""")
        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            val path = file.invariantSeparatorsPath
            val moneyFile = "/core/" in path || "/data/" in path || "/domain/" in path
            file.readLines().forEachIndexed { i, raw ->
                val trimmed = raw.trim()
                if (trimmed.startsWith("/*") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("//")
                ) {
                    return@forEachIndexed
                }
                // Ignore string literals and trailing comments so URLs/messages do not look like float literals.
                val line = raw.replace(Regex("\"[^\"]*\""), "\"\"").substringBefore("//")
                val moneyLine =
                    Regex(
                        "amount|price|total|money|balance|piaster",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(line)
                if ((moneyFile || moneyLine) &&
                    floating.containsMatchIn(line)
                ) {
                    offenders += "${file.name}:${i + 1}: ${line.trim()}"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "NoFloatingPointMoney violations:\n" + offenders.joinToString("\n"),
            )
        }
    }
}

tasks.register("checkFast") {
    group = "verification"
    description = "Fast gate: ktlint + detekt + money scan + lint + unit tests + debug APK"
    // koverXmlReportDebug writes app/build/reports/kover/reportDebug.xml, which scripts/quality/coverage-gate.py reads.
    dependsOn("ktlintCheck", "detekt", checkNoFloatMoney, "lintDebug", "koverXmlReportDebug", "assembleDebug")
}
