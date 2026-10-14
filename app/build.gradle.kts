plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.nanovoice"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.nanovoice"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.nanovoice.CalendarDeviceRunner"
        // Phones only: the sherpa-onnx native libraries are large, so ship just arm64.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            // Personal app, no store release: sign with the debug key so the release build can be installed.
            // A release build is not debuggable, so ART can compile it ahead of time (debug builds stay slow).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    // Exercise the same non-debuggable build used on the phone, without shipping test code in the app.
    testBuildType = "release"
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":local-router"))
    implementation(project(":actions"))
    implementation(project(":agent-client"))
    implementation(project(":voice"))
    implementation(project(":link-preview"))
    implementation(files(rootProject.file("voice/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")).builtBy(":voice:downloadSherpa"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.unifiedpush.connector)
    // Reads barcodes in photos on the phone (offline): only the number goes to Open Food Facts.
    implementation(libs.zxing.core)
    // Live camera for the barcode scanner.
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.kotlinx.coroutines.core)
    // Markdown of the agent's replies: parsed by the CommonMark reference library, drawn by the app (ui/Markdown.kt).
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasks)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
