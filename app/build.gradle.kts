plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.khoichon.rush"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khoichon.rush"
        minSdk = 28            // ImageDecoder + AnimatedImageDrawable (animated WebP sprites)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-android.1"
    }

    buildTypes {
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

    // sprites/sounds are read straight from the APK (openFd), so they must stay uncompressed
    androidResources {
        noCompress += listOf("ogg", "webp")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
