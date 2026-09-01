package dev.nattle.packsanity.feature.universaltracker.domain

/**
 * The only platform-specific surface needed by the shared UT runtime adapter.
 *
 * Each function receives and returns one JSON document. Android JNI and iOS C interop both call the
 * same `packsanity_python_*` C façade; neither exposes a CPython object to Kotlin.
 */
interface UniversalTrackerJsonRuntime : AutoCloseable {
    fun loadTrackerPackage(requestJson: String): String

    fun loadWorld(requestJson: String): String

    fun initializeTracker(requestJson: String): String

    fun updateState(stateJson: String): String

    override fun close()
}

fun interface UniversalTrackerJsonRuntimeFactory {
    fun create(): UniversalTrackerJsonRuntime
}
