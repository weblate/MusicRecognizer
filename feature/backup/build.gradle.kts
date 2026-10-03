plugins {
    alias(libs.plugins.musicrecognizer.android.feature)
    alias(libs.plugins.musicrecognizer.android.library.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mrsep.musicrecognizer.feature.backup"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)

    implementation(libs.kotlinx.serializationJson)
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.csv)
    implementation(libs.reorderable)
    implementation(libs.androidx.workKtx)
    implementation(libs.hilt.ext.work)
    ksp(libs.hilt.ext.compiler)
}