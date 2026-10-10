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
        versionCode = 3
        versionName = "1.2.1"
        // On-device engine: 64-bit ARM phones only (keeps the APK small and the build fast)
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                // Release is essential: a debug build of llama.cpp is painfully slow.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod",
                )
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // llama.cpp is fetched by the GitHub workflow (or by hand, see README) into src/main/cpp/llama.cpp
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
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
