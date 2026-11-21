plugins {
    kotlin("multiplatform")
}
kotlin {
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("packsanity") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/packsanity.def"))
            includeDirs.allHeaders(project.file(providers.gradleProperty("packsanityIosHeaders").get()))
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("dev.nattle.packsanity:loader:${providers.gradleProperty("packsanityVersion").get()}")
    }
}
tasks.register<JavaExec>("runSmoke") {
    val jvm = kotlin.jvm().compilations.getByName("main")
    classpath(jvm.output.allOutputs, jvm.runtimeDependencyFiles)
    mainClass.set("JvmSmokeKt")
    environment("PACKSANITY_NATIVE_DIR", providers.gradleProperty("packsanityNativeDir").get())
    args(providers.gradleProperty("packsanityFixtureDir").get())
}
