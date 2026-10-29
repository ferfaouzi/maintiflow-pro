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
rootProject.name = "MaintiFlowPro"
include(":app")
include(":server")
// Serveur PC / Raspberry Pi (même code serveur, fichier .jar).
include(":server-pc")
