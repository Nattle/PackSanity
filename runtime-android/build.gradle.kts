plugins {
    id("com.android.library")
    `maven-publish`
}

val runtimeInputs = rootProject.layout.buildDirectory.dir("release-inputs/android")

android {
    namespace = "dev.nattle.packsanity.ut"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.named("main") {
        java.srcDir(rootProject.file("loader/src/jvmMain/java"))
        assets.srcDir(runtimeInputs.get().dir("assets").asFile)
        jniLibs.srcDir(runtimeInputs.get().dir("jni").asFile)
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

val verifyRuntimeInputs =
    tasks.register("verifyRuntimeInputs") {
        val directory = runtimeInputs.get().asFile
        inputs.dir(directory)
        doLast {
            val required =
                listOf(
                    "jni/arm64-v8a/libpacksanity_lua_jni.so",
                    "jni/arm64-v8a/libpacksanity_python.so",
                    "jni/arm64-v8a/libpython3.13.so",
                    "assets/packsanity/packsanity-runtime-id.txt",
                    "assets/packsanity/runtime.zip",
                )
            required.forEach {
                require(directory.resolve(it).isFile) { "Missing Android release input: $it" }
            }
        }
    }

tasks.named("preBuild") { dependsOn(verifyRuntimeInputs) }

publishing {
    repositories {
        maven {
            name = "releaseStaging"
            url = rootProject.layout.buildDirectory.dir("release/maven").get().asFile.toURI()
        }
    }
    publications.register<MavenPublication>("release") {
        artifactId = "runtime-android"
        pom {
            name = "PackSanity Android runtime"
            description = "Android Lua and CPython bridges with the locked PackSanity payload"
            url = "https://github.com/Nattle/PackSanity"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
        }
        afterEvaluate { from(components["release"]) }
    }
}
