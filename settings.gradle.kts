pluginManagement {
    plugins {
        kotlin("jvm") version "2.1.20"
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "YaMusicSDK"
