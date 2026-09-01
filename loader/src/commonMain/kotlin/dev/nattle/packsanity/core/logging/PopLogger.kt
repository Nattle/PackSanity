package dev.nattle.packsanity.core.logging

interface PopLogger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String, throwable: Throwable? = null)

    fun error(message: String, throwable: Throwable? = null)
}

fun createPlatformLogger(): PopLogger = NoOpPopLogger

object NoOpPopLogger : PopLogger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(message: String, throwable: Throwable?) = Unit

    override fun error(message: String, throwable: Throwable?) = Unit
}

class RedactingPopLogger(private val delegate: PopLogger) : PopLogger {
    override fun debug(message: String) = delegate.debug(message.redactCredentials())

    override fun info(message: String) = delegate.info(message.redactCredentials())

    override fun warn(message: String, throwable: Throwable?) =
        delegate.warn(message.redactCredentials(), throwable)

    override fun error(message: String, throwable: Throwable?) =
        delegate.error(message.redactCredentials(), throwable)

    private fun String.redactCredentials(): String {
        val keys = listOf("password=", "pass=", "token=", "secret=")
        return keys.fold(this) { redacted, key -> redacted.replaceAfterKey(key) }
    }

    private fun String.replaceAfterKey(key: String): String {
        val start = indexOf(key, ignoreCase = true)
        if (start < 0) return this
        val valueStart = start + key.length
        val valueEnd =
            indexOfAny(charArrayOf(' ', '&', ',', ';'), startIndex = valueStart).let { index ->
                if (index == -1) length else index
            }
        return replaceRange(valueStart, valueEnd, "<redacted>")
    }
}
