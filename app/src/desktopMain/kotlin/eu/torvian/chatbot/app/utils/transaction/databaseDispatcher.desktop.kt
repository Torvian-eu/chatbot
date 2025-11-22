package eu.torvian.chatbot.app.utils.transaction

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A single-threaded dispatcher for database operations on the desktop platform.
 */
actual val databaseDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO.limitedParallelism(1)
