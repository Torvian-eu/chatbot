package eu.torvian.chatbot.app.utils.misc

/**
 * A simplified external interface for the browser's Console API, compatible with Kotlin/Wasm.
 * It explicitly uses String for arguments, as per Wasm JS interop rules.
 *
 * Console methods typically take a primary message and then optional additional arguments.
 * We model this by having a `message: String` and `vararg optionalParams: String`.
 */
external interface Console {
    // Note: The actual JS console functions take Any, but for Wasm interop,
    // we limit it to String as that's what we primarily pass.
    fun debug(message: String, vararg optionalParams: String)
    fun info(message: String, vararg optionalParams: String)
    fun warn(message: String, vararg optionalParams: String)
    fun error(message: String, vararg optionalParams: String)
}

/**
 * The global browser `console` object.
 * Declared 'external val' to indicate it's a JavaScript global variable.
 */
external val console: Console

/**
 * Actual implementation of [KmpLogger] for the Kotlin/Wasm (JavaScript) target,
 * using the browser's `console` API.
 */
class WasmKmpLogger(private val tag: String) : KmpLogger {
    override fun trace(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.debug("[$tag] $message ${throwable.stackTraceToString()}")
        } else {
            console.debug("[$tag] $message")
        }
    }

    override fun debug(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.debug("[$tag] $message ${throwable.stackTraceToString()}")
        } else {
            console.debug("[$tag] $message")
        }
    }

    override fun info(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.info("[$tag] $message ${throwable.stackTraceToString()}")
        } else {
            console.info("[$tag] $message")
        }
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.warn("[$tag] $message ${throwable.stackTraceToString()}")
        } else {
            console.warn("[$tag] $message")
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.error("[$tag] $message ${throwable.stackTraceToString()}")
        } else {
            console.error("[$tag] $message")
        }
    }

    override fun fatal(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.error("[$tag] $message ${throwable.stackTraceToString()}")
        } else {
            console.error("[$tag] $message")
        }
    }
}

/**
 * Actual implementation of the [createKmpLogger] factory function for the Kotlin/Wasm (JavaScript) target.
 * This function provides [WasmKmpLogger] instances.
 */
actual fun createKmpLogger(tag: String): KmpLogger {
    return WasmKmpLogger(tag)
}