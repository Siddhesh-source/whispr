plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin: no Android, no I/O libraries. Depends on nothing but
// coroutines and javax.inject annotations.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
