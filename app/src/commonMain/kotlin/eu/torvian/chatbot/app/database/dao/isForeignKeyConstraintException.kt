package eu.torvian.chatbot.app.database.dao

/**
 * Platform-specific check for foreign key constraint exceptions.
 *
 * @return true if the exception indicates a foreign key constraint violation, false otherwise.
 */
internal expect fun Throwable.isForeignKeyConstraintException(): Boolean