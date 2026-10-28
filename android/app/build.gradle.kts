plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dynamicdroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dynamicdroid.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 7
        versionName = "1.3.2"
    }

    buildFeatures { viewBinding = false }
    sourceSets.getByName("test").java.srcDir("../tests")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
