plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Coordinates for JitPack: com.github.narakai.SynthezKit-Android:<module>:<tag>
allprojects {
    group = "com.github.narakai.SynthezKit-Android"
    version = "0.1.1"
}
