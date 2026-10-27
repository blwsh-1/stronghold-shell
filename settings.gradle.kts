pluginManagement {
    repositories {
        // 阿里云镜像优先（国内直连快），官方源兜底
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先（国内直连快），官方源兜底
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // nodejs-mobile 发布在 JitPack
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "StrongholdShell"
include(":app")
