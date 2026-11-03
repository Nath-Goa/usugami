plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Usugami OCR's text recognition engine: Tencent's ncnn, built from the git submodule
// third_party/ncnn with only what the PaddleOCR models need, and a small JNI bridge, as one native
// library (libusugami_ocr.so). Only the ocr edition of :app depends on this module, so the other two
// editions contain none of it (and don't need the submodule or CMake; Gradle still wants the NDK to
// configure this module).
android {
    namespace = "io.github.nath_goa.usugami.ocr"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }
    buildTypes {
        // Phones are ARM; x86_64 is only for emulators (debug builds and device tests).
        debug { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
        release { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
