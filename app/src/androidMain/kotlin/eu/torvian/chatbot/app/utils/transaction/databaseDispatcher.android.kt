package eu.torvian.chatbot.app.utils.transaction

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * A single-threaded dispatcher for database operations on Android.
 */
actual val databaseDispatcher: CoroutineDispatcher
    get() = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
