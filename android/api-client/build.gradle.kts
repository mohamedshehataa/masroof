// Kotlin client generated from ../contracts/openapi.yaml at build time (never hand-edited, never committed).
// Separate module so generated code is exempt from allWarningsAsErrors/ktlint/detekt.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

kotlin { jvmToolchain(21) }

val generatedDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(rootProject.file("../contracts/openapi.yaml").toURI().toString())
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
