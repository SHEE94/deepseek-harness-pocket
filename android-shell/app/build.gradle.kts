plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.android"
    compileSdk = 35

    defaultConfig {
        // EXACTLY 15 characters — hard constraint if Termux-prefix ELF repatching is used later.
        applicationId = "com.dsh.android"
        minSdk = 26
        // targetSdk 28 is load-bearing: with 29+ the app may not execve() its own files (node/bash die).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-skeleton"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // keep crash stacks readable until M4
        }
    }

    // Engine assets ship pre-compressed (.tar.gz inside the APK): storing them
    // raw avoids a 1.2 GB in-memory deflate in compressDebugAssets (OOM'd the
    // build, device-user requirement: bundle assets, never skimp).
    androidResources {
        noCompress += "gz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // gecko flavor (M2+): implementation("org.mozilla.geckoview")
}
