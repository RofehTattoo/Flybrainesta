plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.flybrain"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.flybrain"
        minSdk = 24
        targetSdk = 35
        versionCode = 131
        versionName = "1.17.0"
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    jvmToolchain(17)
}
