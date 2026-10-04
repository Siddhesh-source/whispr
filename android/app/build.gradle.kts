plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The debug build talks to the local docker-compose backend through
// `adb reverse tcp:8080 tcp:8080` (works for emulators and USB phones).
// Loopback is used rather than 10.0.2.2/LAN IPs because Android 17 blocks
// local-network access without the ACCESS_LOCAL_NETWORK runtime permission,
// which a messenger with an internet-hosted server should never request.
// Override with -Pwhispr.serverUrl=...
val debugServerUrl = providers.gradleProperty("whispr.serverUrl").getOrElse("http://127.0.0.1:8080/")

android {
    namespace = "dev.whispr.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.whispr.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", "\"$debugServerUrl\"")
        }
        release {
            // No production server yet. Release builds must set this explicitly; HTTPS only.
            buildConfigField("String", "SERVER_URL", "\"https://api.whispr.invalid/\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by libsignal-android (uses java.time etc. on older API levels).
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            // libsignal-client's desktop natives are for JVM tests only.
            excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so")
        }
        jniLibs {
            excludes += setOf("**/libsignal_jni_testing.so")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:designsystem"))
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Screens must take colors, sizes and type from the design system
// (:core:designsystem). Fails the build on literals in app sources.
val checkDesignTokens by tasks.registering {
    group = "verification"
    description = "Fails if app sources hard-code colors, dp or sp values."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    val root = projectDir
    inputs.files(sources)
    doLast {
        val rules = mapOf(
            "color literal" to Regex("""Color\(0x"""),
            "named color" to
                Regex("""Color\.(Red|Green|Blue|Black|White|Gray|Grey|Yellow|Cyan|Magenta|LightGray|DarkGray)\b"""),
            "dp/sp literal" to Regex("""\b\d+(\.\d+)?\.(dp|sp)\b"""),
        )
        val violations = sources.files.sorted().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                rules.entries.firstOrNull { it.value.containsMatchIn(line) }
                    ?.let { "${file.relativeTo(root)}:${i + 1}: ${it.key}: ${line.trim()}" }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Use WhisprTheme tokens instead of literals:\n" + violations.joinToString("\n"))
        }
    }
}
tasks.named("check") { dependsOn(checkDesignTokens) }
