
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.euroraptor"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.euroraptor"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
