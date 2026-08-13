// CI 环境（GitHub Actions 等）直连官方源；本地 proot 环境走国内镜像加速。
// 注意：pluginManagement 块是独立脚本作用域，不能用顶层变量，需内联环境判断。
pluginManagement {
    repositories {
        if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) {
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
        if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) {
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