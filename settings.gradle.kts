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
        // Termux terminal-emulator + terminal-view (Apache-2.0) via JitPack.
        // JitPack builds them from the termux-app v0.118.1 tag on demand.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "codex-android"
include(":app")
