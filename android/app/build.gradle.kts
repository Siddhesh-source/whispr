import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

// The debug build talks to the local docker-compose backend through
// `adb reverse tcp:8080 tcp:8080` (works for emulators and USB phones).
// Loopback is used rather than 10.0.2.2/LAN IPs because Android 17 blocks
// local-network access without the ACCESS_LOCAL_NETWORK runtime permission,
// which a messenger with an internet-hosted server should never request.
// Override with -Pwhispr.serverUrl=...
val debugServerUrl = providers.gradleProperty("whispr.serverUrl").getOrElse("http://127.0.0.1:8080/")

// Release builds: the server URL (HTTPS) and at least two SPKI pins
// ("sha256/<base64>", comma-separated: the live key and a backup). Checked
// when a release task runs, so debug builds and CI need none of this.
// -Pwhispr.allowUnpinned=true builds without pins (self-hosters who rotate
// certificates through a CA they trust); docs/DEPLOYMENT.md explains the trade-off.
val releaseServerUrl = providers.gradleProperty("whispr.releaseServerUrl").getOrElse("")

// The newest release's update manifest (written by release.yml). One stable URL:
// GitHub redirects "latest" to the newest published release.
val updateUrl = providers.gradleProperty("whispr.updateUrl")
    .getOrElse("https://github.com/Siddhesh-source/whispr/releases/latest/download/update.json")
val certPins = providers.gradleProperty("whispr.certPins").getOrElse("")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val allowUnpinned = providers.gradleProperty("whispr.allowUnpinned").getOrElse("false").toBoolean()

// Release signing: WHISPR_KEYSTORE_FILE / _PASSWORD, WHISPR_KEY_ALIAS /
// WHISPR_KEY_PASSWORD from the environment (CI), or android/keystore.properties
// (git-ignored) with storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)
val keystoreFile = signingValue("WHISPR_KEYSTORE_FILE", "storeFile")

// Screen screenshots run only with -Pwhispr.screenshots or a Roborazzi task.
val runScreenshots = providers.gradleProperty("whispr.screenshots").isPresent ||
    gradle.startParameter.taskNames.any { it.contains("roborazzi", ignoreCase = true) }

// Firebase (push) is optional. Without these values the app builds and runs
// with push disabled. Put them in android/firebase.properties (git-ignored),
// copied from the Firebase console's google-services.json.
val firebase = Properties().apply {
    rootProject.file("firebase.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun firebaseValue(key: String) =
    firebase.getProperty(key) ?: providers.environmentVariable("WHISPR_" + key.uppercase()).orNull ?: ""

android {
    namespace = "dev.whispr.android"
    compileSdk = 37
    // Pinned so release builds can strip native debug info (libsignal ships
    // ~110 MB of it per ABI). CI installs this version; see release.yml.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.whispr.android"
        minSdk = 26
        targetSdk = 37
        versionName = "0.1.0-beta.3"
        // Derived, so every release is an upgrade: MAJOR*1_000_000 + MINOR*10_000 +
        // PATCH*100 + beta number (99 for a final release). 0.1.0-beta.2 is 10002.
        versionCode = versionCodeOf(versionName!!)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // libsignal and libwebrtc are native: ship phones (arm) and emulators (x86_64) only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseValue("app_id")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseValue("api_key")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseValue("project_id")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebaseValue("sender_id")}\"")
    }
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = rootProject.file(keystoreFile)
                storePassword = signingValue("WHISPR_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("WHISPR_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("WHISPR_KEY_PASSWORD", "keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", "\"$debugServerUrl\"")
            buildConfigField("String", "CERT_PINS", "\"\"")
            // Development builds never update themselves.
            buildConfigField("String", "UPDATE_URL", "\"\"")
        }
        release {
            buildConfigField("String", "SERVER_URL", "\"$releaseServerUrl\"")
            buildConfigField("String", "CERT_PINS", "\"${certPins.joinToString(",")}\"")
            buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
            signingConfig = signingConfigs.findByName("release")
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
        unitTests.all { test ->
            // Screen screenshots are for human review; see core/designsystem/build.gradle.kts.
            if (!runScreenshots) test.filter.excludeTestsMatching("*ScreenshotTest")
        }
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
}

// Fail release builds that would ship without a real server, pins or a signature.
val checkReleaseConfig = tasks.register("checkReleaseConfig") {
    group = "verification"
    description = "Fails if the release server URL, certificate pins or signing key are missing."
    val url = releaseServerUrl
    val pins = certPins
    val unpinned = allowUnpinned
    val signed = keystoreFile != null
    doLast {
        val pinFormat = Regex("^sha256/[A-Za-z0-9+/]{43}=$")
        val problems = buildList {
            if (!url.startsWith("https://")) add("set -Pwhispr.releaseServerUrl=https://...")
            if (pins.size < 2 && !unpinned) {
                add("set -Pwhispr.certPins=sha256/...,sha256/... (live + backup key) or -Pwhispr.allowUnpinned=true")
            }
            if (pins.any { !pinFormat.matches(it) }) add("pins must look like sha256/<base64 of 32 bytes>")
            if (!signed) add("provide a signing key (WHISPR_KEYSTORE_* or android/keystore.properties)")
        }
        if (problems.isNotEmpty()) {
            val list = problems.joinToString("") { System.lineSeparator() + " - " + it }
            throw GradleException("Release build misconfigured:$list")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseConfig) }

roborazzi {
    outputDir.set(file("screenshots"))
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
    implementation(libs.androidx.lifecycle.process)
    // QR scanning: zxing-cpp (open source, on-device, no Google services) on a CameraX preview.
    implementation(libs.zxing.cpp)
    implementation(libs.webrtc)
    implementation(libs.okhttp) // in-app updates (update/Updater.kt)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.firebase.messaging) {
        // Delivery-metrics telemetry to Google (Firelog). Not needed for
        // receiving wake-ups; messaging treats the transport as optional.
        exclude(group = "com.google.firebase", module = "firebase-datatransport")
        exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
        exclude(group = "com.google.android.datatransport", module = "transport-runtime")
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.zxing.core)
}

// Screens must take colors, sizes and type from the design system
// (:core:designsystem). Fails the build on literals in app sources.
val checkDesignTokens = tasks.register("checkDesignTokens") {
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

/** See defaultConfig: a monotonic versionCode from a "MAJOR.MINOR.PATCH[-beta.N]" versionName. */
fun versionCodeOf(name: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""").matchEntire(name)
        ?: error("versionName must look like 1.2.3 or 1.2.3-beta.4, got $name")
    val (major, minor, patch, beta) = m.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100 && (beta.isEmpty() || beta.toInt() in 1..98)) {
        "versionName part out of range: $name"
    }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (beta.toIntOrNull() ?: 99)
}
