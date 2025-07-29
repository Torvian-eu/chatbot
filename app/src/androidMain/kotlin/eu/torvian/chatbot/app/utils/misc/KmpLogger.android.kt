package eu.torvian.chatbot.app.utils.misc

import android.util.Log

/**
 * Actual implementation of [KmpLogger] for the Android target, using Android's Log API.
 */
class AndroidKmpLogger(private val tag: String) : KmpLogger {
    override fun debug(message: String, throwable: Throwable?) {
        Log.d(tag, message, throwable)
    }

    override fun info(message: String, throwable: Throwable?) {
        Log.i(tag, message, throwable)
    }

    override fun warn(message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

/**
 * Actual implementation of the [createKmpLogger] factory function for the Android target.
 * This function provides [AndroidKmpLogger] instances.
 */
actual fun createKmpLogger(tag: String): KmpLogger {
    return AndroidKmpLogger(tag)
}

