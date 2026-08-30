// 모듈이 하나뿐이라 루트 프로젝트가 곧 앱 모듈이다.
// app/app/ 로 한 겹 더 넣어봐야 관례를 만족시키는 것 외에 얻는 게 없다.
pluginManagement {
    repositories {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "samsung-power-direct"
