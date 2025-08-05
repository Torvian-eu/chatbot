package eu.torvian.chatbot.server.main

/**
 * Interface for managing the database schema.
 *
 * Implementations of this interface are responsible for creating and dropping
 * the necessary database tables for the chatbot's data model.
 */
interface DataManager {
    /**
     * Creates all necessary database tables.
     */
    suspend fun createTables()

    /**
     * Drops all database tables.
     */
    suspend fun dropTables()

    /**
     * Checks if the database is empty.
     */
    suspend fun isDatabaseEmpty(): Boolean
}