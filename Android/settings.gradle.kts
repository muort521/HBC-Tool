pluginManagement {
    repositories {
        // 国内镜像优先（dl.google.com 常被屏蔽），官方仓库兜底
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先（dl.google.com 常被屏蔽），官方仓库兜底
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        // Chaquopy 需要的仓库
        maven { url = uri("https://chaquo.com/maven") }
    }
}

rootProject.name = "HBC-Tool-Android"
include(":app")
