plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("third_party/llama.cpp/examples/llama.android/lib/src/main/java"))
        manifest.srcFile(rootProject.file("third_party/llama.cpp/examples/llama.android/lib/src/main/AndroidManifest.xml"))
    }

    // The CMake build was previously disabled here (nothing pointed AGP at CMakeLists.txt),
    // which is why vision-chat.so / ai-chat.so never actually got compiled into the app --
    // System.loadLibrary("vision-chat") was always going to fail with "library not found" on
    // every device, since the .so genuinely was never built, not just missing on this one.
    // No `version` is pinned here on purpose: the Android SDK's own downloadable CMake only
    // goes up to 3.22.1, but this project's CMakeLists.txt requires CMake >= 3.31.6, so it
    // needs a system CMake on PATH new enough to satisfy that (e.g. Termux's `pkg install
    // cmake`), not the SDK-managed one -- pinning a version string here that sdkmanager can't
    // actually provide would just swap one build failure for another.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
}
