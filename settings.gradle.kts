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

rootProject.name = "book-reader"
include(":core")
include(":app")
// 搜书/下载引擎单独一个模块：它是"可拔掉的第三方工具"，不该和阅读引擎互相牵连
include(":booksource")
