pluginManagement {
    repositories {
        // 国内网络把 aliyun 这两行放最前面，拉依赖会快很多
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SopCam"
include(":app")
