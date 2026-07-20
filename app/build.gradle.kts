plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.untrustedtranslations.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.untrustedtranslations.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 17
        versionName = "1.1.0"
    }
    signingConfigs {
        create("release") {
            // Keystore lives outside version control; keep keystore/release.jks backed up —
            // losing it means users must uninstall before any future update installs.
            val keystoreFile = rootProject.file("keystore/release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("UT_KEYSTORE_PASSWORD") ?: "untrusted-translations-2026"
                keyAlias = "untrusted"
                keyPassword = System.getenv("UT_KEYSTORE_PASSWORD") ?: "untrusted-translations-2026"
            }
        }
    }
    buildTypes {
        release {
            if (rootProject.file("keystore/release.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
        }
        // F-Droid variant: no Google ML Kit (closed-source); local engines only.
        create("foss") {
            dimension = "distribution"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.keepDebugSymbols += "**/*.so"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":llama-runtime"))
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    "fullImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-japanese:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-korean:16.0.1")
    "fullImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")
    "fullImplementation"("com.google.mlkit:translate:17.0.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")
}
