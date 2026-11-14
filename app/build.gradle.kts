
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.hotbetanalyzer"

    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hotbetanalyzer"

        minSdk = 26
        targetSdk = 35

        versionCode = 11
        versionName = "0.9.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    // Android Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Google ML Kit OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Room Kotlin Code Generation
    ksp("androidx.room:room-compiler:2.6.1")
}
