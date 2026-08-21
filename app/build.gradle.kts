plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sopcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sopcam"
        minSdk = 26              // MediaStore RELATIVE_PATH 其实要 29，26 只是编译下限
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-stage1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false   // 阶段一先不混淆，等功能稳了再开
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 内置模型：不依赖 Play Services，车间断网也能扫。代价是 APK 大 2–3MB
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // 纯 Java，无 NDK。选它是因为它允许自定义 LuminanceSource ——
    // ML Kit 只收 Bitmap，内部怎么转灰度控制不了
    implementation("com.google.zxing:core:3.5.3")

    // 端侧大模型。带 arm64 原生库，APK 会明显变大，构建也会变慢。
    // 模型文件本身两三个 GB，不打进包里，运行时从存储读
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
