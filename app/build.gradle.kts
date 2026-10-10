plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saeed.llamachat"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.saeed.llamachat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    // The native library is cross-compiled ahead of time by native/build-native.sh (the
    // NDK on this machine cannot run through AGP: it ships x86_64 host binaries only).
    // It is shipped as a prebuilt jniLib so the Gradle build stays offline and fast.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

// Fail early and clearly if the native library was never built, instead of silently
// producing an APK that crashes with UnsatisfiedLinkError on the device.
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val so = file("src/main/jniLibs/arm64-v8a/libllama_jni.so")
        check(so.exists()) {
            "Missing ${so.absolutePath}\n" +
                "Build it first:  bash native/build-native.sh"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}