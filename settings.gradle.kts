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
        maven("https://repository-cdn.liferay.com/nexus/content/repositories/gradle-plugins/")
    }
}

rootProject.name = "DualPathVPN"
include(":app")
