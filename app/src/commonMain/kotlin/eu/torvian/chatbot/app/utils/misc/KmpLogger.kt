package eu.torvian.chatbot.app.utils.misc

/**
 * Common logging interface for Kotlin Multiplatform.
 * This interface defines the logging API available in common code.
 * Each platform will provide its actual implementation.
 */
interface KmpLogger {
    fun debug(message: String, throwable: Throwable? = null)
    fun info(message: String, throwable: Throwable? = null)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}

/**
 * Expected top-level function that acts as a factory for [KmpLogger] instances.
 * Each platform will provide an 'actual' implementation of this function.
 *
 * This is the most stable and recommended approach for platform-specific utilities
 * when avoiding DI framework boilerplate in every consuming class and ensuring
 * compatibility with parallel test execution.
 */
expect fun createKmpLogger(tag: String): KmpLogger

/**
 * Helper function to easily get a [KmpLogger] instance for a class.
 * This function uses the platform-specific `createKmpLogger` factory function.
 *
 * Usage: `private val logger = kmpLogger<MyClass>()`
 */
inline fun <reified T> kmpLogger(): KmpLogger {
    return createKmpLogger(T::class.simpleName ?: "Unknown")
}