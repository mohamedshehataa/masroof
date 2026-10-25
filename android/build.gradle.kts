// Plugin classpath is locked too (-> buildscript-gradle.lockfile), as in backend/.
buildscript {
    dependencyLocking { lockAllConfigurations() }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.openapi.generator) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Lock every resolvable configuration so SCA (osv-scanner, Dependabot) sees the full graph (ASVS 15.1.2).
// LENIENT: Android tooling resolves a few OS-specific artifacts (aapt2, protoc), so a lock written on Linux must not
// break a Windows developer; drift is still visible in review. Refresh on Linux with:
//   ./gradlew dependencies buildEnvironment --write-locks   (see android/gradle/README note in docs/DECISIONS.md)
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.LENIENT)
    }
}
