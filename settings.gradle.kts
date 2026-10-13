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
        // nodejs-mobile 发布在 JitPack
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "StrongholdShell"
include(":app")
