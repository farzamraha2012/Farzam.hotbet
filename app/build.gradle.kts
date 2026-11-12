plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.hotbetanalyzer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.hotbetanalyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.9.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
