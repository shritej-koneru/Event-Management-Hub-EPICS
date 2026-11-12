pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EventManagementHub"
include(":app")
// Pure-Java BLE mesh engine (no Android deps).
include(":meshlib")
// Offline mesh backend for Android (BLE transport, mesh manager, foreground service). No screens.
include(":meshandroid")
