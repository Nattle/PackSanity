rootProject.name = "PackSanity"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":loader", ":cli")

if (providers.gradleProperty("enableAndroid").orNull == "true") include(":runtime-android")
