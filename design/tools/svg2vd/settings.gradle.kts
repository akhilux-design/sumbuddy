// Standalone tool that converts design/icons/svg/*.svg into Android VectorDrawables.
// It is NOT part of the app build: never include it from the root settings.gradle.kts.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "svg2vd"
