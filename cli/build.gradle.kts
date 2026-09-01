plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies { implementation(project(":loader")) }

application { mainClass.set("dev.nattle.packsanity.cli.MainKt") }

val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

abstract class WriteJavaHome : DefaultTask() {
    @get:Input abstract val javaHome: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(javaHome.get())
        }
    }
}

tasks.register<WriteJavaHome>("writeJavaHome") {
    javaHome.set(launcher.map { it.metadata.installationPath.asFile.absolutePath })
    outputFile.set(rootProject.layout.buildDirectory.file("java-home.txt"))
}
