pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Meta's Wearables SDK (mwdat-core, mwdat-camera) comes from Maven Central: no token needed.
    }
}

rootProject.name = "Ronie"
include(":app")
