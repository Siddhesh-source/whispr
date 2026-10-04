plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin: no Android, no I/O libraries. Depends on nothing but
// coroutines and javax.inject annotations.
// Java 17 bytecode and API, compiled by whatever JDK runs Gradle (17 or 21).
// -Xjdk-release guarantees no newer JDK APIs slip in, without needing a
// separate JDK 17 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
