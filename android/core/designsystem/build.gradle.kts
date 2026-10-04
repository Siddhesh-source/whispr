plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Screenshot tests are slow and only produce images for human review (they
// have no golden images to compare against), so they are excluded from normal
// test runs. They run with -Pwhispr.screenshots or any Roborazzi task, e.g.
// ./gradlew :core:designsystem:recordRoborazziDebug
val runScreenshots = providers.gradleProperty("whispr.screenshots").isPresent ||
    gradle.startParameter.taskNames.any { it.contains("roborazzi", ignoreCase = true) }

android {
    namespace = "dev.whispr.core.designsystem"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            if (!runScreenshots) test.filter.excludeTestsMatching("*ScreenshotTest")
        }
    }
}

roborazzi {
    outputDir.set(file("screenshots"))
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.compose.material3)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.zxing.core) // QR rendering only; scanning lives in :app

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
