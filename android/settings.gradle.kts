pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK 21 used for JVM unit tests (see build.gradle.kts).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsignal is published only to Signal's own repository.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            name = "SignalBuildArtifacts"
            content { includeGroup("org.signal") }
        }
    }
}

rootProject.name = "whispr"

include(":app")
include(":core:designsystem")
include(":domain")
include(":data")
