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
        versionCode = 145
        versionName = "1.19.4"
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
