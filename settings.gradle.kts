// CI 环境（GitHub Actions 等）直连官方源；本地 proot 环境走国内镜像加速
val isCi = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null

pluginManagement {
    repositories {
        if (isCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://repo.huaweicloud.com/repository/gradle-plugin/")
            maven("https://repo.huaweicloud.com/repository/maven/")
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (isCi) {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://repo.huaweicloud.com/repository/maven/")
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "Kaze-SLauncher"
include(":app")