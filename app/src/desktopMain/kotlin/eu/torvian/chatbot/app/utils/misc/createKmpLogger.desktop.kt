package eu.torvian.chatbot.app.utils.misc

import org.apache.logging.log4j.LogManager

/**
 * Actual implementation of [KmpLogger] for the JVM (Desktop) target, using Log4j2.
 */
class DesktopKmpLogger(private val tag: String) : KmpLogger {
    private val logger = LogManager.getLogger(tag)

    override fun trace(message: String, throwable: Throwable?) {
        logger.trace(message, throwable)
    }

    override fun debug(message: String, throwable: Throwable?) {
        logger.debug(message, throwable)
    }

    override fun info(message: String, throwable: Throwable?) {
        logger.info(message, throwable)
    }

    override fun warn(message: String, throwable: Throwable?) {
        logger.warn(message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        logger.error(message, throwable)
    }

    override fun fatal(message: String, throwable: Throwable?) {
        logger.fatal(message, throwable)
    }
}

/**
 * Actual implementation of the [createKmpLogger] factory function for the JVM (Desktop) target.
 * This function provides [DesktopKmpLogger] instances.
 */
actual fun createKmpLogger(tag: String): KmpLogger {
    return DesktopKmpLogger(tag)
}