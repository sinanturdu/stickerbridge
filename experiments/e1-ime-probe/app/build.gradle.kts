plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.stickerbridge.e1.imeprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stickerbridge.e1.imeprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-e1"
        manifestPlaceholders["probeFileProvider"] = "androidx.core.content.FileProvider"
    }

    buildTypes {
        debug {
            manifestPlaceholders["probeFileProvider"] = "dev.stickerbridge.e1.imeprobe.GifDiagnosticFileProvider"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // Keep bundled test images byte-for-byte (no PNG crunching).
        noCompress += listOf("png", "webp", "gif")
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
