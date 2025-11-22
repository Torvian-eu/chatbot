package eu.torvian.chatbot.app.utils.transaction

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * WASM/JS implementation of the database dispatcher.
 * Uses the default dispatcher with limited parallelism to ensure
 * single-threaded access to the database.
 */
actual val databaseDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default.limitedParallelism(1)
