plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.nanovoice.actions"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    api(project(":local-router"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Talks to Tempo's media session (it accepts controllers, not play-from-search).
    implementation(libs.media3.session)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
