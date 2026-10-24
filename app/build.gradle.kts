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
        versionCode = 2
        versionName = "0.2.0"
        // The owner's development builds say so in the version ("0.1.0-dev"): tools/install-phone.sh sets it,
        // tools/release.sh never does.
        System.getenv("NANOVOICE_VERSION_SUFFIX")?.takeIf { it.matches(Regex("-[a-z0-9.]{1,16}")) }?.let { versionNameSuffix = it }
        testInstrumentationRunner = "dev.nanovoice.CalendarDeviceRunner"
        // Phones only: the sherpa-onnx native libraries are large, so ship just arm64.
        ndk { abiFilters += "arm64-v8a" }
    }
    // A release is signed with a key handed over through the environment so that no password is ever stored:
    // NANOVOICE_KEYSTORE (a PKCS12 file), NANOVOICE_STORE_PASSWORD and, if it is not "nanovoice", NANOVOICE_KEY_ALIAS.
    // tools/release.sh passes the release key, tools/install-phone.sh the owner's development key. Without them (a contributor, the CI) the release build is signed with the local debug key:
    // good for trying it, never for publishing (tools/release.sh refuses it).
    val releaseKeystore = System.getenv("NANOVOICE_KEYSTORE")?.takeIf { File(it).isFile }
    val releasePassword = System.getenv("NANOVOICE_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = File(releaseKeystore)
                storeType = "PKCS12"
                storePassword = releasePassword
                keyAlias = System.getenv("NANOVOICE_KEY_ALIAS") ?: "nanovoice"
                keyPassword = releasePassword
            }
        }
    }
    buildTypes {
        release {
            // A release build is not debuggable, so ART can compile it ahead of time (debug builds stay slow).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
