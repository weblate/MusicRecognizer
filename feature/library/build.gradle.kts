plugins {
    alias(libs.plugins.musicrecognizer.android.feature)
    alias(libs.plugins.musicrecognizer.android.library.compose)
}

android {
    namespace = "com.mrsep.musicrecognizer.feature.library"
}

dependencies {
    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)
}