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
        // blessed-android 通过 JitPack 分发，版本 2.5.3（GitHub release v2.5.3, 2026-05-12）
        maven("https://jitpack.io")
    }
}

rootProject.name = "GuangluCaliper"
include(":app")
