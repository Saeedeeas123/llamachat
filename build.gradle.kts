// Root build file. Plugins are resolved through the version catalog so every module
// shares one frozen toolchain.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}