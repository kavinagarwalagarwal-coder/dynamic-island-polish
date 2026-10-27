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
        versionCode = 5
        versionName = "1.3.0"
    }

    buildFeatures { viewBinding = false }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/wallpaperAssets"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/wallpaperTestAssets"))
    androidResources { noCompress += "tflite" }
    sourceSets.getByName("test").java.srcDir("../tests")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

val prepareWallpaperModels by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("tools/prepare_wallpaper_models.py").absolutePath)
    inputs.file(rootProject.file("tools/prepare_wallpaper_models.py"))
    outputs.dir(layout.buildDirectory.dir("generated/wallpaperAssets"))
}
tasks.named("preBuild").configure { dependsOn(prepareWallpaperModels) }
val prepareWallpaperTestAssets by tasks.registering(Exec::class) {
    dependsOn(prepareWallpaperModels)
    commandLine("python3", rootProject.file("tools/prepare_wallpaper_models.py").absolutePath, "--test")
    inputs.file(rootProject.file("tools/prepare_wallpaper_models.py"))
    outputs.dir(layout.buildDirectory.dir("generated/wallpaperTestAssets"))
}
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(prepareWallpaperTestAssets) }

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-api:1.4.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
