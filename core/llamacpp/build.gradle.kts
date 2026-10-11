plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saeed.llamacpp"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // libllama_jni.so is cross-compiled ahead of time by native/build-native.sh and shipped
    // inside this module, so the AAR carries the engine and consumers need no NDK at all.
    // (The NDK on this device ships x86_64 host binaries only, so a Gradle NDK build is
    // impossible here anyway.)
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Deliberately zero runtime dependencies: everything used here is plain Android framework
// (Context, Uri, Environment, Looper). That keeps the module buildable offline on a network
// where every new coordinate is a download that can fail.
dependencies {
    testImplementation(libs.junit)
}

// Fail early and clearly when the native library was never built, instead of producing an
// AAR (and then an APK) that crashes with UnsatisfiedLinkError on the device.
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val so = file("src/main/jniLibs/arm64-v8a/libllama_jni.so")
        check(so.exists()) {
            "Missing ${so.absolutePath}\nBuild it first:  bash native/build-native.sh"
        }
    }
}