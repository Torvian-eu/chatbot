package eu.torvian.chatbot.server.data.exposed

import eu.torvian.chatbot.server.data.models.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import eu.torvian.chatbot.server.utils.transactions.ExposedTransactionScope
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Paths
import java.io.File

/**
 * Manages the SQLite database connection and schema creation/migration.
 * Implements [E7.S4 - Initialize SQLite Database & Exposed Schema].
 */
class Database {
    private lateinit var database: Database

    /**
     * Establishes the connection to the SQLite database file.
     * Creates the database file in a platform-appropriate location if it doesn't exist.
     */
    fun connect() {
        // Use a platform-appropriate location for the DB file (e.g., user home directory or AppData)
        // For V1.1 Windows, a simple location like user's Documents or AppData is acceptable.
        // Let's use user home for simplicity in V1.1.
        val homeDir = System.getProperty("user.home")
        val dbDir = Paths.get(homeDir, "AIChatApp").toFile()
        if (!dbDir.exists()) {
            dbDir.mkdirs() // Create directory if it doesn't exist
        }
        val dbFile = File(dbDir, "chat.db")
        val dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        database = Database.connect(dbUrl, driver = "org.sqlite.JDBC")

        // Optional: Add logger to see SQL queries (useful for debugging)
        // transaction {
        //     addLogger(StdOutSqlLogger)
        // }
    }

    /**
     * Creates the database schema (tables) if they don't already exist.
     * In V1.1, this uses SchemaUtils.create for simplicity.
     * More complex migrations would be needed in future versions.
     * Runs in a blocking transaction, suitable for application startup.
     */
    fun createSchema() {
        transaction(database) { // Use standard blocking transaction for schema creation
            SchemaUtils.create(
                ChatSessions,
                ChatMessages,
                LLMModels,
                ModelSettings,
                ChatGroups
            )
        }
    }

     /**
      * Provides access to the underlying Exposed Database instance for the [TransactionScope].
      * This is needed by the [ExposedTransactionScope] implementation.
      * @return The Exposed [Database] instance.
      */
    fun getExposedDatabase(): Database = database

    /**
     * Closes the database connection.
     * For SQLite JDBC, this often doesn't require an explicit call,
     * but included for completeness and potential future use with other drivers.
     */
    fun close() {
        // SQLite JDBC driver doesn't have a dedicated close method,
        // connections are usually managed by the transaction manager.
        // For robustness, we can ensure all pending transactions are complete
        // or rely on the JVM shutdown hook registered by Exposed's DataSource.close().
        // Manual close might be needed for other drivers/setups, but less critical for simple SQLite.
    }
}