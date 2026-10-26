// Kotlin client generated from ../contracts/openapi.yaml at build time (never hand-edited, never committed).
// Separate module so generated code is exempt from allWarningsAsErrors/ktlint/detekt.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

kotlin { jvmToolchain(21) }

val generatedDir = layout.buildDirectory.dir("generated/openapi")

// The generator mis-renders two things in this contract: inline map-of-enum (Health.checks) unless inline enums are
// resolved, and then the hoisted `sort` default (-occurredAt). The contract itself is never edited; this build-time copy
// only drops that one default line, which the server applies anyway.
val patchedSpec = layout.buildDirectory.file("openapi/openapi-for-kotlin.yaml")
val patchSpec by tasks.registering {
    val source = rootProject.file("../contracts/openapi.yaml")
    inputs.file(source)
    outputs.file(patchedSpec)
    doLast {
        val lines = source.readLines().filterNot { it.trim() == "default: -occurredAt" }
        patchedSpec.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    }
}

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(patchedSpec.get().asFile.toURI().toString())
    outputDir.set(generatedDir.get().asFile.absolutePath)
    packageName.set("eg.masroof.api")
    apiPackage.set("eg.masroof.api.apis")
    modelPackage.set("eg.masroof.api.models")
    invokerPackage.set("eg.masroof.api.infra")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    // Only apis + models (+ CollectionFormats): the app builds its own Retrofit/OkHttp stack.
    globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to "CollectionFormats.kt"))
    inlineSchemaOptions.set(mapOf("RESOLVE_INLINE_ENUMS" to "true"))
    typeMappings.set(mapOf("AnyType" to "JsonElement"))
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "kotlinx-datetime",
            "useCoroutines" to "true",
            "omitGradleWrapper" to "true",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
}

tasks.named("openApiGenerate") {
    dependsOn(patchSpec)
    // inputSpec is a URI string, which generator 7.26 does not track as a file input: declare the spec explicitly so a
    // contract change always regenerates (a stale client hid BudgetStatus.daysLeft after merging main).
    inputs.file(patchedSpec).withPropertyName("patchedSpec")
    // Stale files from earlier runs must not leak into the build.
    doFirst { delete(generatedDir) }
}

tasks.register("generateApi") {
    group = "openapi"
    description = "Generates the Kotlin API client from contracts/openapi.yaml"
    dependsOn("openApiGenerate")
}

sourceSets.main {
    kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
}
tasks.named("compileKotlin") { dependsOn("generateApi") }

dependencies {
    api(libs.retrofit)
    api(libs.retrofit.kotlinx.serialization)
    api(libs.retrofit.scalars)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// Lock only what ships (runtime classpath); tooling and test classpaths are intentionally not locked.
configurations.matching { it.name == "runtimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
dependencyLocking { lockMode.set(LockMode.LENIENT) }
