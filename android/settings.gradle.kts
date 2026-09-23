pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "jarvis-android"

// `core` is plain Kotlin (the Claude brain, tools, storage) so it can be built and
// unit-tested without the Android SDK: JARVIS_CORE_ONLY=1 ./gradlew :core:test
include(":core")
if (System.getenv("JARVIS_CORE_ONLY") == null) {
    include(":app")
}
