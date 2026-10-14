import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.nanovoice.voice"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// sherpa-onnx (Apache-2.0) ships an official AAR on GitHub, not on Maven Central. It is downloaded at build
// time and verified against a pinned SHA-256, instead of committing a 38 MB binary.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar").asFile

val downloadSherpa = tasks.register("downloadSherpa") {
    description = "Downloads the sherpa-onnx AAR and verifies its checksum"
    outputs.file(sherpaAar)
    doLast {
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
            sherpaAar.parentFile.mkdirs()
            val part = File(sherpaAar.parentFile, sherpaAar.name + ".part")
            URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${sherpaAar.name}").toURL()
                .openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            check(sha256(part) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
            check(part.renameTo(sherpaAar)) { "cannot move the sherpa-onnx AAR into place" }
        }
    }
}

dependencies {
    // Compile against the AAR here; the app module packages it (a library module cannot bundle a local AAR).
    compileOnly(files(sherpaAar).builtBy(downloadSherpa))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vosk.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
