pluginManagement {
    repositories {
        // CI 上直接走官方源；本地 Termux 编译若嫌慢，可自行加 aliyun 镜像
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
