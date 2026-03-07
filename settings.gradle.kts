pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                // Include semua grup yang berasal dari JitPack
                includeGroupByRegex("com\\.github\\..*")
                includeGroupByRegex("com\\.gitlab\\..*")
            }
        }
    }
}

rootProject.name = "KoperasiKitaGodangUlu"
include(":app")