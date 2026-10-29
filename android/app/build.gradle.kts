plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dynamicdroid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dynamicdroid.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 11
        versionName = "1.6.0"
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

    kotlinOptions { jvmTarget = "17" }

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
    debugImplementation(fileTree(mapOf("dir" to "libs/debug", "include" to listOf("*.aar", "*.jar"))))
    releaseImplementation(fileTree(mapOf("dir" to "libs/release", "include" to listOf("*.aar", "*.jar"))))

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
