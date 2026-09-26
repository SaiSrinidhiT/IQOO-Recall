// AGP 9 has built-in Kotlin (it depends on KGP 2.2.10 at runtime). Putting newer KGP/KSP on the
// buildscript classpath is the documented way to use a higher Kotlin version.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
