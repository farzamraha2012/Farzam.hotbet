plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "com.example.hotbetanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hotbetanalyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.0"
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
    implementation("androidx.core:core-ktx:1.15.0")

    // OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Local permanent database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background database operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
