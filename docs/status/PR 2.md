Okay, here is the code for **PR 2: Server Infrastructure - Database Schema & Transaction Scope**, assigned to Alex for implementation and Eric for review.
This PR sets up the core backend infrastructure related to data persistence: the SQLite database connection and schema definition using Exposed, and the `TransactionScope` utility for managing coroutine-safe transactions. This is a foundational PR that many subsequent backend PRs will depend on.
It includes KDoc documentation for all public components.
* * *
**PR 2: Server Infrastructure - Database Schema & Transaction Scope**
*   **Assignee:** Alex
    
*   **Reviewer:** Eric
    
*   **Description:** Set up the SQLite database connection logic (E7.S4) and define the full V1.1 database schema using Exposed (E7.S4). This includes tables and foreign key constraints for sessions (with group ID), messages (with parent/children IDs), groups, models, and settings. Implement the `TransactionScope` utility pattern (based on Rogier's suggestion) using Exposed's `newSuspendedTransaction` for safe, coroutine-based database transactions (E7.S6 aspect). Note that timestamps are stored as Long (milliseconds since epoch) and message roles use Exposed's enumeration type.
    
*   **Stories Addressed:** E7.S4 (Database Init & Schema), E7.S6 (Coroutines - TransactionScope part). Foundation for all data persistence stories (E2.S1-E2.S6, E3.S3-E3.S4, Epic 4, Epic 5 backend, Epic 6 backend).
    
*   **Key Files:**
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/Database.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatSessions.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatMessages.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatGroups.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/LLMModels.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ModelSettings.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/TransactionScope.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/ExposedTransactionScope.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/TransactionMarker.kt`
    
*   `server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/CoroutineContextExtensions.kt`
    

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/Database.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.server.data.models.* // Import all table objects
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Paths // For platform-appropriate path
import java.io.File // For file handling
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
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatGroups.kt
package eu.torvian.chatbot.server.data.models
import eu.torvian.chatbot.common.models.ChatGroup
import org.jetbrains.exposed.dao.id.LongIdTable
/**
*   Exposed table definition for chat session groups.
    
*   Corresponds to the [ChatGroup] entity.
    
*     
    
*   @property name The unique name of the chat group
    
*   @property createdAt The timestamp when the group was created
    
*/
object ChatGroups : LongIdTable("chat_groups") {
val name = varchar("name", 255).uniqueIndex()
val createdAt = long("created_at").default(System.currentTimeMillis())
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatMessages.kt
package eu.torvian.chatbot.server.data.models
import eu.torvian.chatbot.common.models.ChatMessage.Role
import eu.torvian.chatbot.common.models.ChatMessage
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
/**
*   Exposed table definition for chat messages.
    
*   Corresponds to the [ChatMessage] entity.
    
*     
    
*   @property sessionId Reference to the parent chat session
    
*   @property role The role of the message sender (user or assistant)
    
*   @property content The text content of the message
    
*   @property createdAt Timestamp when the message was created
    
*   @property updatedAt Timestamp when the message was last updated
    
*   @property parentMessageId Reference to the parent message in a thread (null for root messages)
    
*   @property childrenMessageIds JSON array of child message IDs for threading
    
*   @property modelId Reference to the LLM model used for assistant messages
    
*   @property settingsId Reference to the model settings used for assistant messages
    
*/
object ChatMessages : LongIdTable("chat_messages") {
val sessionId = reference("session_id", ChatSessions, onDelete = ReferenceOption.CASCADE)
val role = enumerationByName<Role>("role", 50)
val content = text("content")
val createdAt = long("created_at").default(System.currentTimeMillis())
val updatedAt = long("updated_at").default(System.currentTimeMillis())
val parentMessageId = reference("parent_message_id", ChatMessages, onDelete = ReferenceOption.SET_NULL).nullable()
val childrenMessageIds = text("children_message_ids").default("[]")
val modelId = reference("model_id", LLMModels, onDelete = ReferenceOption.SET_NULL).nullable()
val settingsId = reference("settings_id", ModelSettings, onDelete = ReferenceOption.SET_NULL).nullable()
// Add indices for sessionId and parentMessageId for efficient querying (E2.S4, E1.S4 backend)
init {
    index(false, sessionId)
    index(false, parentMessageId)
}
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ChatSessions.kt
package eu.torvian.chatbot.server.data.models
import eu.torvian.chatbot.common.models.ChatSession
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
/**
*   Exposed table definition for chat sessions.
    
*   Corresponds to the [ChatSession] entity.
    
*     
    
*   @property name The name of the chat session
    
*   @property createdAt Timestamp when the session was created
    
*   @property updatedAt Timestamp when the session was last updated
    
*   @property groupId Reference to the chat group this session belongs to
    
*   @property currentModelId Reference to the currently selected LLM model for this session
    
*   @property currentSettingsId Reference to the currently selected model settings for this session
    
*   @property currentLeafMessageId Reference to the leaf message in the last active branch
    
*/
object ChatSessions : LongIdTable("chat_sessions") {
val name = varchar("name", 255)
val createdAt = long("created_at").default(System.currentTimeMillis())
val updatedAt = long("updated_at").default(System.currentTimeMillis())
val groupId = reference("group_id", ChatGroups, onDelete = ReferenceOption.SET_NULL).nullable()
val currentModelId = reference("current_model_id", LLMModels, onDelete = ReferenceOption.SET_NULL).nullable()
val currentSettingsId = reference("current_settings_id", ModelSettings, onDelete = ReferenceOption.SET_NULL).nullable()
val currentLeafMessageId = reference("current_leaf_message_id", ChatMessages, onDelete = ReferenceOption.SET_NULL).nullable()
// Add index for groupId to speed up grouped session queries (E6.S2)
init {
    index(false, groupId)
    // Index for currentLeafMessageId removed based on provided code
}
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/models/LLMModels.kt
package eu.torvian.chatbot.server.data.models
import eu.torvian.chatbot.common.models.LLMModel
import org.jetbrains.exposed.dao.id.LongIdTable
/**
*   Exposed table definition for LLM model configurations.
    
*   Corresponds to the [LLMModel] entity.
    
*     
    
*   @property name The unique name of the LLM model
    
*   @property baseUrl The base URL for API requests to the model
    
*   @property apiKeyId Reference ID to the securely stored API key (not the key itself)
    
*   @property type The type of LLM model (e.g., "openai", "openrouter", "custom")
    
*/
object LLMModels : LongIdTable("llm_models") {
val name = varchar("name", 255).uniqueIndex()
val baseUrl = varchar("base_url", 512)
val apiKeyId = varchar("api_key_id", 255).nullable().uniqueIndex()
val type = varchar("type", 50)
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ModelSettings.kt
package eu.torvian.chatbot.server.data.models
import eu.torvian.chatbot.common.models.ModelSettings as CommonModelSettings
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
/**
*   Exposed table definition for LLM model settings profiles.
    
*   Corresponds to the [CommonModelSettings] entity.
    
*     
    
*   @property modelId Reference to the parent LLM model
    
*   @property name The name of the settings profile (e.g., "Default", "Creative", "Strict")
    
*   @property systemMessage The system message to use with this settings profile
    
*   @property temperature The temperature parameter for controlling randomness in generation
    
*   @property maxTokens The maximum number of tokens to generate
    
*   @property customParamsJson Arbitrary JSON for extra model-specific parameters
    
*/
object ModelSettings : LongIdTable("model_settings") {
val modelId = reference("model_id", LLMModels, onDelete = ReferenceOption.CASCADE)
val name = varchar("name", 255)
val systemMessage = text("system_message").nullable()
val temperature = float("temperature").nullable()
val maxTokens = integer("max_tokens").nullable()
val customParamsJson = text("custom_params_json").nullable()
// Add index for modelId to speed up settings lookup by model (E4.S5)
init {
    index(false, modelId)
}
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/CoroutineContextExtensions.kt
package eu.torvian.chatbot.server.utils.transactions
import kotlin.coroutines.CoroutineContext
/**
*   Provides extension properties related to the coroutine context for transaction management utilities.
    
*/
val CoroutineContext.isInTransaction: Boolean
/**
 * Checks whether a transaction managed by [TransactionScope] is active in the current coroutine context,
 * by checking for the presence of a [TransactionMarker].
 */
get() = this[TransactionMarker] != null
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/ExposedTransactionScope.kt
package eu.torvian.chatbot.server.utils.transactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlin.coroutines.coroutineContext
/**
*   Exposed-based implementation of [TransactionScope] using coroutine-safe transactions.
    
*     
    
*   Ensures only one transaction is created per logical use-case initiating the transaction boundary,
    
*   and prevents problematic nesting by checking for a [TransactionMarker] in the coroutine context.
    
*     
    
*   If a transaction is already active (marked by [TransactionMarker]), the block is executed directly
    
*   within that context. Otherwise, a new [newSuspendedTransaction] is started on the IO dispatcher,
    
*   and the [TransactionMarker] is added to the context for subsequent checks within the transaction.
    
*     
    
*   @param db The target [Database] to run the transaction on.
    
*/
class ExposedTransactionScope(private val db: Database) : TransactionScope {
override suspend fun <T> transaction(block: suspend () -> T): T {
    return if (coroutineContext[TransactionMarker] != null) {
        // Already in a transaction managed by this scope; avoid creating a new nested one.
        // The existing TransactionMarker in context confirms we are in a suspend transaction initiated by THIS scope.
        block()
    } else {
        // Not in a transaction managed by this scope; create a new one.
        // Add TransactionMarker to the new context.
        withContext(Dispatchers.IO + TransactionMarker()) {
             // newSuspendedTransaction handles setting up the Exposed transaction in this CoroutineContext
            newSuspendedTransaction(context = coroutineContext, db = db) {
                block() // Execute the suspending block within the transaction
            }
        }
    }
}
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/TransactionMarker.kt
package eu.torvian.chatbot.server.utils.transactions
import java.util.UUID
import kotlin.coroutines.CoroutineContext
/**
*   Coroutine context element used to mark that a transaction initiated by [TransactionScope] is active
    
*   in the current coroutine context.
    
*     
    
*   When present in the [CoroutineContext], it signals that the current coroutine
    
*   is already inside a transaction managed by this scope, allowing the implementation
    
*   to avoid creating redundant or problematic nested transactions.
    
*     
    
*   This marker is internal to the application's transaction management utility.
    
*     
    
*   @property id Optional unique ID to trace the transaction context (useful for logging/debugging).
    
*/
data class TransactionMarker(val id: UUID = UUID.randomUUID()) : CoroutineContext.Element {
override val key: CoroutineContext.Key<*> = Key
companion object Key : CoroutineContext.Key<TransactionMarker>
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/utils/transactions/TransactionScope.kt
package eu.torvian.chatbot.server.utils.transactions
import kotlin.coroutines.CoroutineContext
/**
*   Represents an abstraction for managing database transactions.
    
*     
    
*   Allows the service layer to explicitly define transaction boundaries,
    
*   without embedding `transaction {}` blocks in repository or domain logic.
    
*   Ensures the use of coroutine-safe suspending transactions.
    
*/
interface TransactionScope {
/**
 * Runs the given [block] of code inside a single transaction.
 *
 * If a transaction is already active in the current coroutine context (marked by [TransactionMarker]),
 * the block is run within that existing transaction context.
 * Otherwise, a new suspending transaction is started on the IO dispatcher.
 *
 * @param block The suspending code block to run inside the transaction.
 * @return The result of the block.
 */
suspend fun <T> transaction(block: suspend () -> T): T
}
```

This concludes the code for PR 2. Alex will create these files in the `server` module under the `data.exposed` and `utils.transactions` packages, add the necessary dependencies (Exposed, kotlinx-datetime (though timestamps changed to long, this dependency might still be needed elsewhere), JNA if not already added for E5.S1 in this PR), and submit it for review.
