// Test-only, separately installed app: exercises real cross-package notification delivery.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.dynamicdroid.fixture"
    compileSdk = 35
    defaultConfig { applicationId = "com.dynamicdroid.fixture"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "fixture" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
