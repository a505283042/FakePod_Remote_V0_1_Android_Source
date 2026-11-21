pluginManagement {
    repositories {
        mavenCentral()
        google {
            content {
                excludeGroupByRegex("org\\.jetbrains.*")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                excludeGroupByRegex("org\\.jetbrains.*")
            }
        }
    }
}

rootProject.name = "FakePodRemote"
include(":app")