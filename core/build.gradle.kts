plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.okhttp) // HttpUrl: links are read as browsers read them (LinkPolicy)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
