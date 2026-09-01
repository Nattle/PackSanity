import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library") apply false
    kotlin("plugin.serialization")
    `maven-publish`
}

val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"

if (enableAndroid) apply(plugin = "com.android.kotlin.multiplatform.library")

val generatedHost = layout.buildDirectory.dir("generated/host")

abstract class GenerateHostSource : DefaultTask() {
    @get:InputDirectory abstract val hostDirectory: DirectoryProperty
    @get:Input abstract val fragmentNames: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        fun quoted(source: String): String =
            "\"" +
                source
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("$", "\\$")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") +
                "\""
        val chunks =
            fragmentNames
                .get()
                .joinToString("") {
                    hostDirectory.get().file("$it.lua").asFile.readText()
                }
                .chunked(16000)
        val output = outputDirectory.get().file("BundledPopTrackerHost.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package dev.nattle.packsanity.feature.packruntime.data\n" +
                "internal fun bundledPopTrackerHostSource(): String = listOf(\n" +
                chunks.joinToString(",\n") { quoted(it) } +
                "\n).joinToString(\"\")\n"
        )
    }
}

val generateHost =
    tasks.register<GenerateHostSource>("generateHost") {
        hostDirectory.set(rootProject.layout.projectDirectory.dir("lua/poptracker_host"))
        fragmentNames.set(
            listOf(
                "registry",
                "seeded_objects",
                "object_state",
                "providers",
                "script_host",
                "pack_io",
                "calendar",
                "archipelago",
                "rule_snapshots",
            )
        )
        outputDirectory.set(generatedHost)
    }

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    jvmToolchain(21)
    if (enableAndroid) {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>(
            "android"
        ) {
            namespace = "dev.nattle.packsanity.loader"
            compileSdk = 37
            minSdk = 27
        }
    }
    // Mobile consumers implement the small native/runtime boundary. Native metadata is opt-in
    // so pack developers do not need Xcode or an Android SDK to run the JVM integration tools.
    if (providers.gradleProperty("enableIos").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(generateHost)
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}

tasks
    .matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateHost) }

tasks.withType<JavaCompile>().configureEach { options.release.set(11) }

// Keep authored-code and dependency notices with locally published binary/source artifacts.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

publishing {
    repositories {
        maven {
            name = "releaseStaging"
            url = rootProject.layout.buildDirectory.dir("release/maven").get().asFile.toURI()
        }
    }
    publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
        pom {
            name.set("PackSanity loader")
            description.set("Standalone PopTracker and APWorld loading contracts and runtimes")
            url.set("https://github.com/Nattle/PackSanity")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                    distribution.set("repo")
                }
            }
        }
    }
}
