package eu.torvian.chatbot.app.utils.transaction

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A platform-specific dispatcher for database operations. For SQLite, this must be a single-threaded dispatcher.
 */
expect val databaseDispatcher: CoroutineDispatcher