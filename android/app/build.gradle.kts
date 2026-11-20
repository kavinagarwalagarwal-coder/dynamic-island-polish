plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One permanent signing key, so every new build installs as an update over the last one
// (Android refuses updates signed with a different key). CI decodes it from the repo secrets into
// DD_KEYSTORE; without it (forks, local builds) the usual per-machine debug key is used.
val sharedKeystore = System.getenv("DD_KEYSTORE")?.let { File(it) }?.takeIf { it.isFile }

// Each GitHub build gets a higher version number than the one before, so it installs as an update.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.dynamicdroid.app"
    // 37 = Android 17. Compiling against it changes nothing at runtime; targetSdk does.
    compileSdk = 37

    defaultConfig {
        // A unique ID, so the phone never mistakes this app for another app that happens to share
        // a common name like "com.dynamicdroid.app". The code package stays com.dynamicdroid.app.
        applicationId = "io.github.kavinagarwalagarwal.dynamicdroid"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "com.dynamicdroid.app.IslandTestRunner"
        versionCode = if (buildNumber != null) 100 + buildNumber else 15
        versionName = if (buildNumber != null) "1.7.0.$buildNumber" else "1.7.0"
    }

    // Two builds, each with its own app ID so both can be installed side by side:
    //  - standard: the current build, targets Android 15 and runs on Android 8 to 17.
    //  - android17: opts in to Android 17's rules (predictive back, background audio limits,
    //    large-screen resizing). For phones already on Android 16 or 17. Shows as "Dynamic Droid 17".
    flavorDimensions += "platform"
    productFlavors {
        create("standard") {
            dimension = "platform"
            targetSdk = 35
        }
        create("android17") {
            dimension = "platform"
            targetSdk = 37
            applicationIdSuffix = ".android17"
            versionNameSuffix = "-android17"
        }
    }

    buildFeatures {
        viewBinding = false
        compose = true
    }
    sourceSets.getByName("test").java.srcDir("../tests")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    signingConfigs {
        if (sharedKeystore != null) create("shared") {
            storeFile = sharedKeystore
            storePassword = System.getenv("DD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DD_KEY_ALIAS") ?: "dynamicdroid"
            keyPassword = System.getenv("DD_KEY_PASSWORD") ?: System.getenv("DD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared")
        }
        create("performance") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Installable build: the permanent key when CI has it, else this machine's debug key.
            signingConfig = signingConfigs.getByName(if (sharedKeystore != null) "shared" else "debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint { abortOnError = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.2.0")
    implementation("androidx.navigation3:navigation3-runtime:1.0.1")
    implementation("androidx.navigation3:navigation3-ui:1.0.1")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.savedstate:savedstate-ktx:1.3.1")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")
    implementation("io.coil-kt.coil3:coil-svg:3.4.0")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.2"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("org.commonmark:commonmark:0.28.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.28.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.10")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")

    // Compose UI tests (drags on real settings controls); the manifest only ships in debug builds.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
