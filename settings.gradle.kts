pluginManagement {
    repositories {
        maven { url = uri("https://repo.spring.io/milestone") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://repo.spring.io/milestone") }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "osc"

include(
    "backend:metadata-engine",
    "backend:persistence",
    "backend:query-engine",
    "backend:automation",
    "backend:security",
    "backend:api",
    "backend:ai",
    "backend:integrations"
)
