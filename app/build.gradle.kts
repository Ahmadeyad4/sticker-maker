plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.stickermaker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.stickermaker"
        minSdk = 24
        targetSdk = 34
        // Every GitHub build gets a higher number; the in-app updater compares against it.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
        // The bundled FFmpeg library only ships arm64 (almost all current phones).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // A fixed signing key is required so new builds can install over old ones.
    // The workflow restores it from the KEYSTORE_B64 secret into app/stable.jks.
    val stableKey = file("stable.jks")
    if (stableKey.exists()) {
        signingConfigs {
            create("stable") {
                storeFile = stableKey
                storeType = "pkcs12"
                storePassword = "stickermaker"
                keyAlias = "stickermaker"
                keyPassword = "stickermaker"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // FFmpeg with libvpx (VP9), used for Telegram animated stickers. Community-maintained ffmpeg-kit fork.
    implementation("dev.ffmpegkit-maintained:ffmpeg:6.0.3")
    // Lottie renders Telegram animated (TGS) stickers so they can be turned into WhatsApp stickers.
    implementation("com.airbnb.android:lottie:6.7.1")
}
