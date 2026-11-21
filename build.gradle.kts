plugins {
    id("com.android.library") version "9.5.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.5.1" apply false
    id("com.diffplug.spotless") version "8.10.3"
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}

allprojects {
    group = "dev.nattle.packsanity"
    version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()
}

spotless {
    kotlin {
        target(
            fileTree("loader/src") { include("**/*.kt") },
            fileTree("cli/src") { include("**/*.kt") },
        )
        ktfmt().kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktfmt().kotlinlangStyle()
    }
}
