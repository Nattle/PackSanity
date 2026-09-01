pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven { url = uri(providers.gradleProperty("packsanityRepository").get()) }
            }
            filter { includeGroup("dev.nattle.packsanity") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "packsanity-release-consumer"
include(":loader-smoke", ":android-smoke")
