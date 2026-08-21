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
        // Xposed API artifacts (de.robv.android.xposed:api)
        maven { url = uri("https://api.xposed.info/") }
        // Fallback mirror for the Xposed API in case api.xposed.info is unreachable
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RingVibe"
include(":app")
