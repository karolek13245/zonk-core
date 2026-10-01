plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}
android {
    namespace = "com.example.aiapp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.aiapp" // TODO: change before publishing (must be unique)
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // On-device mode: limit to 64-bit ARM phones
        // ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // ---- On-device llama.cpp engine (optional) ----
    // 1) git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
    // 2) Uncomment below, sync, build.
    // 3) Push a .gguf model to the device (see README -> On-device model).
    // externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
