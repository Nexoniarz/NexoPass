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
    }
}
rootProject.name = "NexoPass"

// shared/ holds the code both apps compile: core (passwords, vault, export)
// and the Compose UI. Each app adds it as a source folder.
include(":android", ":desktop")
