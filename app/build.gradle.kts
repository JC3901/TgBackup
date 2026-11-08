plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.jc3901.tgbackup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jc3901.tgbackup"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            // Fixed key kept in the repo so every GitHub build can update the
            // installed app in place. Fine for a personal, sideloaded app.
            storeFile = file("tgbackup.keystore")
            storePassword = "tgbackup"
            keyAlias = "tgbackup"
            keyPassword = "tgbackup"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("telethon==1.45.0")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
