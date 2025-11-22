package eu.torvian.chatbot.app.database.dao

/**
 * No-op implementation for foreign key constraint check on WASM/JS.
 * 
 * TODO: Implement proper check if/when SQLDelight supports WASM.
 */
internal actual fun Throwable.isForeignKeyConstraintException(): Boolean = false

