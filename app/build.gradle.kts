plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.hello"
    compileSdk = 34

    defaultConfig {
        ndk { abiFilters.add("arm64-v8a") }
        applicationId = "com.example.hello"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            // 开启压缩：APK 从约 8MB 降到 3MB 左右
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += "onnx"
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
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
}
