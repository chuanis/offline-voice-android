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
        mavenCentral() // 关键：Vosk 的家
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Aegis Voice"
include(":app")